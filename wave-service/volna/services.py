"""Сборка зависимостей: откуда брать треки (Navidrome) и звучание (AudioMuse)."""

from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Protocol

import numpy as np

from .config import Settings
from .models import Candidate, LibrarySnapshot, Media, Track
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

    def _fetch(self, getter, ids: list[str]) -> dict:
        """Ответ «нет данных» кэшируется, сетевая ошибка — нет (повторим в следующий раз)."""

        def one(track_id: str):
            try:
                return True, getter(track_id)
            except Exception:  # noqa: BLE001 — сеть или формат ответа, в лог и дальше
                log.debug("Не удалось получить данные для %s", track_id, exc_info=True)
                return False, None

        results = dict(zip(ids, self.pool.map(one, ids)))
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


def build_deps(settings: Settings) -> Deps:
    store = Store(settings.db_path)
    if settings.demo:
        from .demo import DemoWorld

        world = DemoWorld()
        return Deps(library=world, sim=world, store=store, vectors=Vectors(world, store), demo=True)

    from .audiomuse import AudioMuse
    from .navidrome import Navidrome

    if not settings.audiomuse_url:
        raise SystemExit("Задайте AUDIOMUSE_URL в .env: без AudioMuse волне не из чего строить вкус")
    library = Navidrome(settings.navidrome_url, settings.navidrome_user, settings.navidrome_password, store)
    sim = AudioMuse(settings.audiomuse_url, settings.audiomuse_token)
    return Deps(library=library, sim=sim, store=store, vectors=Vectors(sim, store))
