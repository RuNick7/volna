"""Сборка зависимостей: откуда брать треки (Navidrome) и звучание (AudioMuse)."""

from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np

from .config import ServerConfig
from .models import Candidate, LibrarySnapshot, Media, Playlist, Track
from .store import Store

log = logging.getLogger(__name__)

DAY = 86400.0


class Library(Protocol):
    """То, что умеет Navidrome: история, оценки, метаданные, поток."""

    def ping(self) -> bool: ...
    def sync(self) -> LibrarySnapshot: ...
    def random_songs(self, n: int = 50) -> list[Track]: ...
    def songs(self, ids: list[str]) -> dict[str, Track]: ...
    def scrobble(self, track_id: str, submission: bool, time_ms: int | None = None) -> None: ...
    def star(self, item_id: str, entity: str = "track") -> None: ...
    def unstar(self, item_id: str, entity: str = "track") -> None: ...
    def set_rating(self, item_id: str, rating: int) -> None: ...
    def create_playlist(self, name: str, track_ids: list[str]) -> Playlist: ...
    def stream(self, track_id: str, range_header: str | None = None) -> Media: ...
    def cover(self, cover_id: str, size: int = 600) -> Media: ...


class Similarity(Protocol):
    """То, что умеет AudioMuse: векторы звучания и поиск похожего."""

    def ping(self) -> bool: ...
    def alchemy(self, add: list[str], subtract: list[str] = (), n: int = 30, temperature: float = 0.6) -> list[Candidate]: ...
    def similar(self, track_id: str, n: int = 20) -> list[Candidate]: ...
    def embedding(self, track_id: str) -> np.ndarray | None: ...
    def features(self, track_id: str) -> dict | None: ...


class Vectors:
    """Эмбеддинги и признаки с кэшем в SQLite; недостающие запрашиваются параллельно."""

    RETRY_AFTER = DAY

    def __init__(self, sim: Similarity, store: Store, workers: int = 8):
        self.sim = sim
        self.store = store
        self.pool = ThreadPoolExecutor(max_workers=workers)
        self.warm_pool = ThreadPoolExecutor(max_workers=6)  # прогрев не отнимает потоки у живых запросов

    def warm(self, ids: list[str]) -> int:
        """Загрузить в кэш векторы и признаки, которых ещё нет. Возвращает, сколько запросили."""
        _, missing = self.store.embeddings(ids, self.RETRY_AFTER)
        if missing:
            self.store.put_embeddings(self._fetch(self.sim.embedding, missing, self.warm_pool))
        _, missing_features = self.store.features(ids, self.RETRY_AFTER)
        if missing_features:
            self.store.put_features(self._fetch(self.sim.features, missing_features, self.warm_pool))
        return len(missing) + len(missing_features)

    def cached(self, ids: list[str]) -> int:
        """Сколько треков уже есть в кэше векторов (вместе с известными «нет данных»)."""
        _, missing = self.store.embeddings(ids, self.RETRY_AFTER)
        return len(ids) - len(missing)

    def get(self, ids: list[str]) -> dict[str, np.ndarray]:
        ids = list(dict.fromkeys(ids))
        found, missing = self.store.embeddings(ids, self.RETRY_AFTER)
        if missing:
            fetched = self._fetch(self.sim.embedding, missing)
            self.store.put_embeddings(fetched)
            found.update({i: v for i, v in fetched.items() if v is not None})
        return found

    def features(self, ids: list[str]) -> dict[str, dict]:
        ids = list(dict.fromkeys(ids))
        found, missing = self.store.features(ids, self.RETRY_AFTER)
        if missing:
            fetched = self._fetch(self.sim.features, missing)
            self.store.put_features(fetched)
            found.update({i: f for i, f in fetched.items() if f is not None})
        return found

    def _fetch(self, getter, ids: list[str], pool: ThreadPoolExecutor | None = None) -> dict:
        """Ответ «нет данных» кэшируется, сетевая ошибка — нет (повторим в следующий раз)."""

        def one(track_id: str):
            try:
                return True, getter(track_id)
            except Exception:  # noqa: BLE001 — сеть или формат ответа, в лог и дальше
                log.debug("Не удалось получить данные для %s", track_id, exc_info=True)
                return False, None

        results = dict(zip(ids, (pool or self.pool).map(one, ids)))
        failed = sum(1 for ok, _ in results.values() if not ok)
        if failed:
            log.warning("AudioMuse не ответил для %d из %d треков", failed, len(ids))
        return {i: value for i, (ok, value) in results.items() if ok}


@dataclass
class Deps:
    library: Library
    sim: Similarity
    store: Store
    vectors: Vectors
    demo: bool = False
    background_io: bool = False  # скробблы и звёзды в Navidrome отправлять в фоне, не задерживая ответ

    def run_io(self, fn, *args, **kwargs) -> None:
        """Сетевое действие с Navidrome: в фоне (в сервисе) или сразу (в тестах и симуляции)."""

        def call() -> None:
            try:
                fn(*args, **kwargs)
            except Exception:  # noqa: BLE001
                log.warning("Navidrome не принял %s", getattr(fn, "__name__", "действие"), exc_info=True)

        if self.background_io:
            _IO_POOL.submit(call)
        else:
            call()


_IO_POOL = ThreadPoolExecutor(max_workers=2)


class NoSimilarity:
    """AudioMuse не подключён: библиотека и плеер работают, а волне не из чего строить вкус."""

    MESSAGE = "AudioMuse не подключён: укажите его адрес в Настройки → Сервер"

    def ping(self) -> bool:
        raise LookupError(self.MESSAGE)

    def alchemy(self, add, subtract=(), n=30, temperature=0.6) -> list[Candidate]:
        return []

    def similar(self, track_id: str, n: int = 20) -> list[Candidate]:
        return []

    def embedding(self, track_id: str):
        raise LookupError(self.MESSAGE)  # ошибка, а не «нет данных»: такое не кэшируется

    def features(self, track_id: str):
        raise LookupError(self.MESSAGE)


def build_deps(server: ServerConfig | None, data_dir: Path) -> Deps:
    """None — демо-библиотека; иначе Navidrome и (если указан) AudioMuse."""
    if server is None:
        from .demo import DemoWorld

        store = Store(data_dir / "demo.db")
        world = DemoWorld()
        return Deps(library=world, sim=world, store=store, vectors=Vectors(world, store), demo=True)

    from .audiomuse import AudioMuse
    from .navidrome import Navidrome

    store = Store(data_dir / f"server-{server.key}.db")
    library = Navidrome(server.navidrome_url, server.navidrome_user, server.navidrome_password, store)
    sim = (
        AudioMuse(server.audiomuse_url, server.audiomuse_token, server.audiomuse_user, server.audiomuse_password)
        if server.audiomuse_url
        else NoSimilarity()
    )
    return Deps(library=library, sim=sim, store=store, vectors=Vectors(sim, store))
