"""HTTP API волны и веб-плеер прототипа.

Сервер сам держит очередь: клиент сообщает, чем закончился трек (дослушал, пропустил
на такой-то секунде, «не нравится»), и в ответ получает следующий. Поэтому любому
клиенту, будь то веб-прототип или будущее Android-приложение, достаточно одной кнопки.
"""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask

from .config import Settings
from .engine import WaveSession
from .models import Track
from .profile import TasteProfile, build_context_profile, build_profile
from .services import Deps, build_deps

log = logging.getLogger(__name__)

WEB_DIR = Path(__file__).resolve().parent / "web"
ModeName = Literal["favorite", "balanced", "discover"]


class StartRequest(BaseModel):
    mode: ModeName = "balanced"


class AdvanceRequest(BaseModel):
    track_id: str
    outcome: Literal["complete", "skip", "dislike", "error"]
    position: float = Field(0.0, ge=0)
    duration: float = Field(0.0, ge=0)


class LikeRequest(BaseModel):
    track_id: str
    liked: bool = True


class ModeRequest(BaseModel):
    mode: ModeName


class ContinueRequest(BaseModel):
    track_ids: list[str] = Field(min_length=1)
    mode: ModeName = "balanced"


class ProfileManager:
    """Профиль пересобирается после 25 событий или раз в 6 часов."""

    REBUILD_EVENTS = 25
    MAX_AGE = 6 * 3600

    def __init__(self, deps: Deps):
        self.deps = deps
        self._profile: TasteProfile | None = None
        self._events = 0
        self._lock = threading.Lock()

    def get(self) -> TasteProfile:
        with self._lock:
            stale = (
                self._profile is None
                or self._events >= self.REBUILD_EVENTS
                or time.time() - self._profile.built_at > self.MAX_AGE
            )
            if stale:
                started = time.time()
                self._profile = build_profile(self.deps)
                self._events = 0
                log.info(
                    "Профиль вкуса собран за %.1f с: %d кластеров",
                    time.time() - started,
                    len(self._profile.clusters),
                )
            return self._profile

    def note_event(self) -> None:
        self._events += 1


def create_app(settings: Settings | None = None, deps: Deps | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    deps = deps or build_deps(settings)
    profiles = ProfileManager(deps)
    sessions: dict[str, WaveSession] = {}
    app = FastAPI(title="Volna", version="0.1.0")
    app.state.deps = deps
    app.state.sessions = sessions

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

    def track_json(t: Track | None, session: WaveSession) -> dict | None:
        if t is None:
            return None
        return {
            "id": t.id,
            "title": t.title,
            "artist": t.artist,
            "album": t.album,
            "duration": t.duration,
            "liked": t.starred,
            "stream": f"/api/stream/{t.id}",
            "cover": f"/api/cover/{t.cover_id or t.id}",
            "reason": session.reason(t.id),
        }

    def state(session: WaveSession) -> dict:
        return {
            "session": session.id,
            "mode": session.mode.name,
            "track": track_json(session.current, session),
            "upcoming": [track_json(t, session) for t in session.queue[:3]],
        }

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

    @app.post("/api/wave/start")
    def start_wave(body: StartRequest) -> dict:
        try:
            profile = profiles.get()
        except Exception as exc:  # noqa: BLE001
            log.exception("Не удалось собрать профиль вкуса")
            raise HTTPException(503, f"Не удалось собрать профиль вкуса: {exc}") from exc
        session = WaveSession(deps, profile, body.mode)
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

    @app.post("/api/wave/{session_id}/mode")
    def set_mode(session_id: str, body: ModeRequest) -> dict:
        session = session_or_404(session_id)
        with session.lock:
            session.set_mode(body.mode)
        return state(session)

    @app.get("/api/wave/{session_id}/debug")
    def debug(session_id: str) -> dict:
        return session_or_404(session_id).snapshot()

    @app.post("/api/continue")
    def continue_playlist(body: ContinueRequest) -> dict:
        """Плейлист закончился: продолжаем похожим на весь плейлист, а не на последнюю песню."""
        taste = profiles.get()
        try:
            context = build_context_profile(deps, body.track_ids, taste)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        session = WaveSession(deps, context, body.mode, taste=taste, exclude=body.track_ids, kind="continue")
        with session.lock:
            session.start()
        remember(session)
        return state(session)

    @app.get("/api/profile")
    def profile() -> dict:
        p = profiles.get()
        return {
            "cold_start": p.cold_start,
            "built_at": p.built_at,
            "clusters": [
                {
                    "label": c.label,
                    "share": round(c.share, 3),
                    "tracks": len(c.members),
                    "completion_rate": c.completion_rate,
                }
                for c in sorted(p.clusters, key=lambda c: -c.share)
            ],
        }

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
