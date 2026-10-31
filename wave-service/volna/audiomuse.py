"""Клиент AudioMuse-AI REST.

Авторизация — API-токен (заголовок `Authorization: Bearer …`) или логин и пароль
веб-интерфейса AudioMuse: тогда клиент входит через `POST /auth`, держит сессионную
cookie (живёт 8 часов) и сам перелогинивается, когда она истекает.
"""

from __future__ import annotations

import threading

import httpx
import numpy as np

from .config import normalize_url
from .models import Candidate


class AudioMuseAuthError(PermissionError):
    pass


def _candidate(row: dict) -> Candidate | None:
    track_id = row.get("item_id") or row.get("id")
    if not track_id:
        return None
    return Candidate(id=str(track_id), title=row.get("title") or "", artist=row.get("author") or row.get("artist") or "")


def _tags(value) -> dict[str, float]:
    """mood_vector / other_features приходят строкой вида "rock:0.54,pop:0.31"."""
    if isinstance(value, dict):
        return {str(k): float(v) for k, v in value.items()}
    tags: dict[str, float] = {}
    for part in str(value or "").split(","):
        name, _, score = part.partition(":")
        try:
            tags[name.strip()] = float(score)
        except ValueError:
            continue
    return tags


def _needs_login(r: httpx.Response) -> bool:
    """Без авторизации AudioMuse отвечает 401 на /api и переадресует на /login остальное."""
    return r.status_code in (401, 403) or (r.is_redirect and "/login" in r.headers.get("location", ""))


class AudioMuse:
    def __init__(
        self,
        url: str,
        token: str = "",
        user: str = "",
        password: str = "",
        timeout: float = 20.0,
        transport: httpx.BaseTransport | None = None,  # для тестов
    ):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        self.http = httpx.Client(base_url=normalize_url(url), timeout=timeout, headers=headers, transport=transport)
        self.token = token
        self.user = user
        self.password = password
        self._login_lock = threading.Lock()
        self._logged_in = False

    @property
    def auth_mode(self) -> str:
        return "token" if self.token else "login" if self.user and self.password else "none"

    def login(self) -> None:
        """Войти логином и паролем веб-интерфейса; сессия придёт cookie `audiomuse_jwt`."""
        r = self.http.post(
            "/auth",
            json={"user": self.user, "password": self.password},
            headers={"X-Requested-With": "XMLHttpRequest"},
        )
        if r.status_code == 401:
            raise AudioMuseAuthError("AudioMuse: неверный логин или пароль")
        if r.status_code == 404:
            raise AudioMuseAuthError("В AudioMuse не включён вход по паролю: укажите API-токен")
        r.raise_for_status()
        self._logged_in = True

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        if self.auth_mode == "login" and not self._logged_in:
            with self._login_lock:
                if not self._logged_in:
                    self.login()
        r = self.http.request(method, path, **kwargs)
        if _needs_login(r) and self.auth_mode == "login":
            with self._login_lock:  # сессия истекла или AudioMuse перезапустился — входим заново
                self._logged_in = False
                self.login()
            r = self.http.request(method, path, **kwargs)
        if _needs_login(r):
            raise AudioMuseAuthError(
                "AudioMuse не принял API-токен" if self.token
                else "AudioMuse требует вход: укажите API-токен или логин и пароль"
            )
        return r

    def ping(self) -> bool:
        # 404 значит «сервер доступен и вход принят, просто такого трека нет»
        r = self._request("GET", "/external/get_score", params={"id": "__volna_ping__"})
        if r.status_code in (200, 404):
            return True
        raise RuntimeError(f"AudioMuse ответил {r.status_code}")

    def alchemy(self, add: list[str], subtract: list[str] = (), n: int = 30, temperature: float = 0.6) -> list[Candidate]:
        """Поиск вокруг нескольких треков сразу, с вычитанием нежелательных."""
        items = [{"id": i, "op": "ADD", "type": "song"} for i in add]
        items += [{"id": i, "op": "SUBTRACT", "type": "song"} for i in subtract]
        r = self._request("POST", "/api/alchemy", json={"items": items, "n": n, "temperature": temperature})
        r.raise_for_status()
        return [c for c in map(_candidate, r.json().get("results") or []) if c]

    def similar(self, track_id: str, n: int = 20) -> list[Candidate]:
        r = self._request(
            "GET", "/api/similar_tracks", params={"item_id": track_id, "n": n, "eliminate_duplicates": "true"}
        )
        r.raise_for_status()
        data = r.json()
        rows = data if isinstance(data, list) else data.get("results") or []
        return [c for c in map(_candidate, rows) if c]

    def embedding(self, track_id: str) -> np.ndarray | None:
        r = self._request("GET", "/external/get_embedding", params={"id": track_id})
        if r.status_code == 404:
            return None
        r.raise_for_status()
        vec = r.json().get("embedding")
        return np.asarray(vec, dtype=np.float32) if vec else None

    def features(self, track_id: str) -> dict | None:
        r = self._request("GET", "/external/get_score", params={"id": track_id})
        if r.status_code == 404:
            return None
        r.raise_for_status()
        s = r.json()
        return {
            "tempo": float(s.get("tempo") or 0),
            "energy": float(s.get("energy") or 0),
            "key": s.get("key") or "",
            "scale": s.get("scale") or "",
            "moods": _tags(s.get("mood_vector")),
            "other": _tags(s.get("other_features")),
        }
