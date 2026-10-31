"""Импорт плейлистов по ссылке: Explo достаёт треки и докачивает недостающие, Volna собирает
из библиотеки плейлист в Navidrome для того, кто импортировал.

Задание идёт в фоне: fetching (Explo читает плейлист) → downloading (докачивает недостающее)
→ matching (Navidrome досканировал, ищем треки в библиотеке) → done или failed. Запуск Explo у
него один на всех, поэтому задания разных пользователей докачивают по очереди.
"""

from __future__ import annotations

import logging
import secrets
import threading
import time
from dataclasses import asdict, dataclass, field
from typing import Callable, Protocol

from .explo import Explo
from .library import LibraryIndex
from .models import Playlist, Track
from .store import Store

log = logging.getLogger(__name__)

IMPORTS_KEY = "imports"
KEEP = 20  # сколько последних импортов помнить у пользователя


@dataclass
class ImportJob:
    id: str
    url: str
    status: str = "queued"
    name: str = ""
    cover: str = ""
    total: int = 0
    found: int = 0
    missing: list[str] = field(default_factory=list)
    playlist_id: str = ""
    message: str = "В очереди"
    started: float = field(default_factory=time.time)
    finished: float | None = None

    def to_dict(self) -> dict:
        return asdict(self)


class ImportTarget(Protocol):
    """Пространство пользователя глазами импорта."""

    user: str
    store: Store

    def index(self, refresh: bool) -> LibraryIndex: ...
    def create_playlist(self, name: str, track_ids: list[str]) -> Playlist: ...
    def scanning(self) -> bool: ...


def match(index: LibraryIndex, tracks: list[dict]) -> tuple[list[Track], list[str]]:
    found, missing, seen = [], [], set()
    for t in tracks:
        track = index.find(t.get("artist", ""), t.get("title", ""), t.get("release", ""))
        if track is None:
            missing.append(f"{t.get('artist', '')} — {t.get('title', '')}")
        elif track.id not in seen:
            seen.add(track.id)
            found.append(track)
    return found, missing


class Importer:
    RUN_TIMEOUT = 3 * 3600
    SCAN_TIMEOUT = 15 * 60

    def __init__(self, explo: Explo, poll: float = 10.0, background: bool = True):
        self.explo = explo
        self.poll = poll
        self.background = background
        self.runs = threading.Lock()  # Explo докачивает по одному плейлисту за раз
        self.jobs: dict[str, list[ImportJob]] = {}  # пользователь → его задания, новые первыми
        self._lock = threading.Lock()

    def list(self, target: ImportTarget) -> list[dict]:
        live = {j.id: j.to_dict() for j in self.jobs.get(target.user.casefold(), [])}
        saved = [j for j in target.store.setting(IMPORTS_KEY) or [] if j["id"] not in live]
        for j in saved:
            if j["status"] not in ("done", "failed"):  # сервис перезапускался посреди импорта
                j.update(status="failed", message="Прервано перезапуском сервиса: запустите импорт ещё раз")
        return sorted([*live.values(), *saved], key=lambda j: -j["started"])[:KEEP]

    def start(self, url: str, target: ImportTarget) -> ImportJob:
        job = ImportJob(id=secrets.token_urlsafe(8), url=url)
        with self._lock:
            self.jobs.setdefault(target.user.casefold(), []).insert(0, job)
        if self.background:
            threading.Thread(target=self._run, args=(job, target), name="volna-import", daemon=True).start()
        else:
            self._run(job, target)
        return job

    # --- задание ---

    def _run(self, job: ImportJob, target: ImportTarget) -> None:
        try:
            job.status, job.message = "fetching", "Explo читает плейлист"
            info = self.explo.add_playlist(job.url)
            job.name = info.get("name") or "Импорт"
            job.cover = info.get("artwork_url", "")
            tracks = self._tracks(info["id"])
            if not tracks:
                raise RuntimeError("Explo не нашёл треков по этой ссылке: плейлист приватный или пустой?")
            job.total = len(tracks)
            found, missing = match(target.index(False), tracks)
            explo_ran = False
            if missing:
                job.status = "downloading"
                self._download(job, info["id"], f"Докачиваю {len(missing)} из {len(tracks)} — это может занять время")
                explo_ran = True
                job.status, job.message = "matching", "Обновляю библиотеку"
                self._wait_scan(target)
                found, missing = match(target.index(True), tracks)
            job.found, job.missing = len(found), missing
            if found:
                job.playlist_id = self._playlist(job, target, found, explo_ran)
            job.status = "done"
            job.message = f"Готово: в библиотеке {len(found)} из {len(tracks)}" + (
                f", не нашлось {len(missing)}" if missing else ""
            )
        except Exception as exc:  # noqa: BLE001 — показываем человеку, что пошло не так
            log.warning("Импорт %s не удался", job.url, exc_info=True)
            job.status, job.message = "failed", str(exc) or "Импорт не удался"
        finally:
            job.finished = time.time()
            self._persist(job, target)

    def _tracks(self, playlist_id: str) -> list[dict]:
        for _ in range(6):  # список треков Explo достаёт сразу после добавления, но не всегда мгновенно
            tracks = self.explo.tracks(playlist_id)
            if tracks:
                return tracks
            time.sleep(self.poll / 2)
        return []

    def _download(self, job: ImportJob, playlist_id: str, progress: str) -> None:
        deadline = time.time() + self.RUN_TIMEOUT
        job.message = progress
        with self.runs:
            while not self.explo.run(playlist_id):  # занят своим расписанием или чужим запуском
                job.message = "Explo занят, жду своей очереди"
                if time.time() > deadline:
                    raise RuntimeError("Explo так и не освободился")
                time.sleep(self.poll)
            job.message = progress
            time.sleep(self.poll / 2)
            while self.explo.running():
                if time.time() > deadline:
                    raise RuntimeError("Explo качает слишком долго: проверьте его журнал")
                time.sleep(self.poll)

    def _wait_scan(self, target: ImportTarget) -> None:
        deadline = time.time() + self.SCAN_TIMEOUT
        time.sleep(self.poll / 2)  # Explo просит Navidrome пересканировать в конце запуска
        while target.scanning() and time.time() < deadline:
            time.sleep(self.poll)

    def _playlist(self, job: ImportJob, target: ImportTarget, found: list[Track], explo_ran: bool) -> str:
        # Explo сам создаёт плейлист у своего аккаунта Navidrome: если импортировал он же, второй не нужен
        if explo_ran and target.user.casefold() == self.explo.system_user().casefold():
            index = target.index(False)
            own = next((p for p in index.playlists.values() if p.name == job.name), None)
            if own is not None:
                return own.id
        return target.create_playlist(job.name, [t.id for t in found]).id

    def _persist(self, job: ImportJob, target: ImportTarget) -> None:
        saved = [j for j in target.store.setting(IMPORTS_KEY) or [] if j["id"] != job.id]
        target.store.put_setting(IMPORTS_KEY, [job.to_dict(), *saved][:KEEP])
