"""HTTP API волны и веб-плеер прототипа.

Сервер сам держит очередь: клиент сообщает, чем закончился трек (дослушал, пропустил
на такой-то секунде, «не нравится»), и в ответ получает следующий. Поэтому любому
клиенту, будь то веб-прототип или будущее Android-приложение, достаточно одной кнопки.
Прослушивания вне волны (альбом, плейлист, поиск) клиент шлёт в /api/events — они
тоже учатся в профиль.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask

from .config import Settings
from .engine import WaveSession, classify, register_playback
from .models import LibrarySnapshot, Track
from .profile import TasteProfile, build_context_profile, build_profile
from .services import Deps, build_deps
from .settings import ACTIVITIES, CHARACTERS, MOODS, WaveSettings
from .store import Event

log = logging.getLogger(__name__)

WEB_DIR = Path(__file__).resolve().parent / "web"
SETTINGS_KEY = "wave_settings"


class Seed(BaseModel):
    type: Literal["track", "album", "artist", "playlist"]
    id: str


class StartRequest(BaseModel):
    settings: dict | None = None  # поверх сохранённых настроек
    seed: Seed | None = None  # «Моя волна по…» треку, альбому, артисту или плейлисту
    device: str = ""


class AdvanceRequest(BaseModel):
    track_id: str
    outcome: Literal["complete", "skip", "dislike", "error"]
    position: float = Field(0.0, ge=0)
    duration: float = Field(0.0, ge=0)


class LikeRequest(BaseModel):
    track_id: str
    liked: bool = True


class EntityLikeRequest(BaseModel):
    type: Literal["track", "album", "artist"]
    id: str
    liked: bool = True


class PlaybackEvent(BaseModel):
    """Прослушивание вне волны: альбом, плейлист, поиск, библиотека."""

    track_id: str
    outcome: Literal["complete", "skip", "dislike"]
    position: float = Field(0.0, ge=0)
    duration: float = Field(0.0, ge=0)
    context_type: Literal["album", "playlist", "artist", "search", "library", "other"] = "other"
    context_id: str = ""
    device: str = ""


class ContinueRequest(BaseModel):
    track_ids: list[str] = Field(min_length=1)
    settings: dict | None = None
    device: str = ""


class ProfileManager:
    """Библиотека синхронизируется раз в 6 часов, профиль пересобирается после 25 событий."""

    REBUILD_EVENTS = 25
    MAX_AGE = 6 * 3600

    def __init__(self, deps: Deps):
        self.deps = deps
        self._profile: TasteProfile | None = None
        self._snapshot: LibrarySnapshot | None = None
        self._events = 0
        self._lock = threading.Lock()

    def snapshot(self) -> LibrarySnapshot:
        with self._lock:
            return self._fresh_snapshot()

    def _fresh_snapshot(self) -> LibrarySnapshot:
        if self._snapshot is None or time.time() - self._snapshot.synced_at > self.MAX_AGE:
            self._snapshot = self.deps.library.sync()
            self._profile = None
        return self._snapshot

    def get(self) -> TasteProfile:
        with self._lock:
            snapshot = self._fresh_snapshot()
            if self._profile is None or self._events >= self.REBUILD_EVENTS:
                started = time.time()
                self._profile = build_profile(self.deps, snapshot)
                self._events = 0
                log.info(
                    "Профиль вкуса собран за %.1f с: %d кластеров",
                    time.time() - started, len(self._profile.clusters),
                )
            return self._profile

    def note_event(self) -> None:
        self._events += 1


def create_app(settings: Settings | None = None, deps: Deps | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    deps = deps or build_deps(settings)
    profiles = ProfileManager(deps)
    sessions: dict[str, WaveSession] = {}
    app = FastAPI(title="Volna", version="0.2.0")
    app.state.deps = deps
    app.state.sessions = sessions

    def saved_settings() -> WaveSettings:
        return WaveSettings.from_dict(deps.store.setting(SETTINGS_KEY))

    def remember(session: WaveSession) -> None:
        sessions[session.id] = session
        while len(sessions) > 20:
            oldest = min(sessions.values(), key=lambda s: s.touched)
            sessions.pop(oldest.id)

    def session_or_404(session_id: str) -> WaveSession:
        session = sessions.get(session_id)
        if session is None:
            raise HTTPException(404, "Сессия не найдена: начните волну заново")
        return session

    def taste_or_503() -> TasteProfile:
        try:
            return profiles.get()
        except Exception as exc:  # noqa: BLE001
            log.exception("Не удалось собрать профиль вкуса")
            raise HTTPException(503, f"Не удалось собрать профиль вкуса: {exc}") from exc

    def track_json(t: Track | None, session: WaveSession) -> dict | None:
        if t is None:
            return None
        return {
            "id": t.id,
            "title": t.title,
            "artist": t.artist,
            "album": t.album,
            "album_id": t.album_id,
            "artist_id": t.artist_id,
            "duration": t.duration,
            "liked": t.starred,
            "stream": f"/api/stream/{t.id}",
            "cover": f"/api/cover/{t.cover_id or t.id}",
            "reason": session.reason(t.id),
        }

    def state(session: WaveSession) -> dict:
        return {
            "session": session.id,
            "kind": session.kind,
            "title": session.title,
            "settings": session.settings.to_dict(),
            "track": track_json(session.current, session),
            "upcoming": [track_json(t, session) for t in session.queue[:3]],
        }

    def seed_tracks(seed: Seed) -> tuple[list[str], str]:
        snapshot = profiles.snapshot()
        if seed.type == "track":
            track = deps.library.songs([seed.id]).get(seed.id)
            return [seed.id], (f"{track.artist} — {track.title}" if track else "трек")
        if seed.type == "album":
            songs = [t for t in snapshot.songs if t.album_id == seed.id]
            return [t.id for t in songs], (songs[0].album if songs else "альбом")
        if seed.type == "artist":
            songs = sorted((t for t in snapshot.songs if t.artist_id == seed.id), key=lambda t: -t.play_count)
            return [t.id for t in songs[:60]], (songs[0].artist if songs else "артист")
        playlist = next((p for p in snapshot.playlists if p.id == seed.id), None)
        return (playlist.track_ids, playlist.name) if playlist else ([], "плейлист")

    # --- служебное ---

    @app.get("/api/health")
    def health() -> dict:
        status = {"mode": "demo" if deps.demo else "live"}
        for name, service in (("navidrome", deps.library), ("audiomuse", deps.sim)):
            try:
                status[name] = bool(service.ping())
            except Exception as exc:  # noqa: BLE001
                status[name] = False
                status[f"{name}_error"] = str(exc)
        return status

    # --- настройки ---

    @app.get("/api/settings")
    def get_settings() -> dict:
        return {
            "settings": saved_settings().to_dict(),
            "options": {
                "character": list(CHARACTERS),
                "mood": list(MOODS),
                "activity": list(ACTIVITIES),
            },
        }

    @app.put("/api/settings")
    def put_settings(patch: dict) -> dict:
        updated = saved_settings().merged(patch)
        deps.store.put_setting(SETTINGS_KEY, updated.to_dict())
        return {"settings": updated.to_dict()}

    # --- волна ---

    @app.post("/api/wave/start")
    def start_wave(body: StartRequest) -> dict:
        taste = taste_or_503()
        wave_settings = saved_settings().merged(body.settings or {})
        if body.seed is None:
            session = WaveSession(deps, taste, wave_settings, device=body.device)
        else:
            ids, title = seed_tracks(body.seed)
            if not ids:
                raise HTTPException(404, "Не нашёл треков для волны по этому элементу")
            try:
                context = build_context_profile(deps, ids, taste)
            except ValueError as exc:
                raise HTTPException(422, str(exc)) from exc
            exclude = ids if body.seed.type != "artist" else [body.seed.id]
            session = WaveSession(
                deps, context, wave_settings, taste=taste, exclude=exclude, kind="seed",
                title=title, context_id=body.seed.id, device=body.device,
            )
        with session.lock:
            session.start()
        remember(session)
        return state(session)

    @app.post("/api/wave/{session_id}/advance")
    def advance(session_id: str, body: AdvanceRequest) -> dict:
        session = session_or_404(session_id)
        with session.lock:
            session.advance(body.track_id, body.outcome, body.position, body.duration)
        profiles.note_event()
        return state(session)

    @app.post("/api/wave/{session_id}/like")
    def like(session_id: str, body: LikeRequest) -> dict:
        session = session_or_404(session_id)
        with session.lock:
            session.like(body.track_id, body.liked)
        profiles.note_event()
        return state(session)

    @app.post("/api/wave/{session_id}/settings")
    def session_settings(session_id: str, patch: dict) -> dict:
        """Поменять настройки на ходу; они же сохраняются как настройки по умолчанию."""
        session = session_or_404(session_id)
        updated = session.settings.merged(patch)
        deps.store.put_setting(SETTINGS_KEY, updated.to_dict())
        with session.lock:
            session.set_settings(updated)
        return state(session)

    @app.post("/api/wave/{session_id}/shake")
    def shake(session_id: str) -> dict:
        session = session_or_404(session_id)
        with session.lock:
            session.shake()
        return state(session)

    @app.get("/api/wave/{session_id}/debug")
    def debug(session_id: str) -> dict:
        return session_or_404(session_id).snapshot()

    @app.post("/api/continue")
    def continue_playlist(body: ContinueRequest) -> dict:
        """Плейлист закончился: продолжаем похожим на весь плейлист, а не на последнюю песню."""
        taste = taste_or_503()
        try:
            context = build_context_profile(deps, body.track_ids, taste)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        session = WaveSession(
            deps, context, saved_settings().merged(body.settings or {}), taste=taste,
            exclude=body.track_ids, kind="continue", device=body.device,
        )
        with session.lock:
            session.start()
        remember(session)
        return state(session)

    # --- сигналы вне волны ---

    @app.post("/api/events")
    def playback_event(body: PlaybackEvent) -> dict:
        """Прослушивание в альбоме, плейлисте или поиске: тоже учит профиль."""
        taste = taste_or_503()
        track = deps.library.songs([body.track_id]).get(body.track_id)
        kind = classify(body.outcome, body.position, body.duration or (track.duration if track else 0.0))
        register_playback(
            deps, taste, body.track_id, kind, track=track, position=body.position, duration=body.duration,
            context_type=body.context_type, context_id=body.context_id, device=body.device,
        )
        profiles.note_event()
        return {"kind": kind}

    @app.post("/api/like")
    def like_entity(body: EntityLikeRequest) -> dict:
        """Лайк трека, альбома или артиста: звезда в Navidrome и событие в профиль."""
        try:
            (deps.library.star if body.liked else deps.library.unstar)(body.id, body.type)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(502, f"Navidrome не принял отметку: {exc}") from exc
        deps.store.add_event(
            Event(ts=time.time(), track_id=body.id, kind="like" if body.liked else "unlike", entity=body.type)
        )
        profiles.note_event()
        return {"ok": True}

    # --- что система знает о вкусе ---

    @app.get("/api/taste")
    def taste_summary() -> dict:
        """«Ваш вкус»: кого дослушиваете, кого пропускаете, откуда треки заходят лучше."""
        p = taste_or_503()
        artists = []
        for name, stats in p.artist_stats.items():
            total = stats.completes + stats.skips
            if total >= 2:
                display = next((t.artist for t in p.track_info.values() if t.artist.lower() == name), name)
                artists.append({"artist": display, "completes": round(stats.completes, 1),
                                "skips": round(stats.skips, 1), "rate": round(stats.completes / total, 2)})
        events = deps.store.events(since=time.time() - 90 * 86400)
        by_source: dict[str, Counter] = defaultdict(Counter)
        for e in events:
            if e.entity == "track" and e.kind in ("complete", "early_skip", "late_skip", "dislike"):
                by_source[e.source or "manual"]["complete" if e.kind == "complete" else "skip"] += 1
        return {
            "signals": p.signals,
            "cold_start": p.cold_start,
            "clusters": [
                {"label": c.label, "share": round(c.share, 3), "tracks": len(c.members),
                 "completion_rate": c.completion_rate}
                for c in sorted(p.clusters, key=lambda c: -c.share)
            ],
            "loved_artists": sorted(artists, key=lambda a: (-a["rate"], -a["completes"]))[:8],
            "skipped_artists": sorted(
                (a for a in artists if a["skips"] >= 1.5), key=lambda a: (a["rate"], -a["skips"])
            )[:8],
            "sources": {
                s: {"complete": c["complete"], "skip": c["skip"],
                    "rate": round(c["complete"] / max(1, c["complete"] + c["skip"]), 2)}
                for s, c in by_source.items()
            },
        }

    # --- медиа и веб-плеер ---

    @app.get("/api/stream/{track_id}")
    def stream(track_id: str, request: Request):
        media = deps.library.stream(track_id, request.headers.get("range"))
        return StreamingResponse(
            iter(media.body),
            status_code=media.status,
            media_type=media.media_type,
            headers=media.headers,
            background=BackgroundTask(media.close),
        )

    @app.get("/api/cover/{cover_id}")
    def cover(cover_id: str):
        media = deps.library.cover(cover_id)
        return StreamingResponse(iter(media.body), status_code=media.status, media_type=media.media_type, headers=media.headers)

    @app.get("/")
    def index() -> FileResponse:
        return FileResponse(WEB_DIR / "index.html")

    app.mount("/", StaticFiles(directory=WEB_DIR), name="web")
    return app
