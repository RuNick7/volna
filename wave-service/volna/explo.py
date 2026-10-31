"""Explo — сервис рядом с Navidrome, который умеет забирать плейлисты по ссылке.

По ссылке на плейлист Spotify или Apple Music он сам достаёт список треков, докачивает
недостающие (Soulseek, YouTube) и обновляет библиотеку Navidrome. Volna говорит с ним через
API его веб-интерфейса: вход логином и паролем, дальше cookie сессии и CSRF-токен.
"""

from __future__ import annotations

import re
import threading

import httpx

# откуда Explo умеет забирать плейлисты; ссылки «поделиться» бывают с ?si=… — это не мешает
SOURCES = {
    "spotify": re.compile(r"^https://open\.spotify\.com/(?:intl-[a-z-]+/)?(?:playlist|album)/[A-Za-z0-9]+"),
    "apple": re.compile(r"^https://music\.apple\.com/.+/(?:playlist|album)/"),
}


def source_of(url: str) -> str | None:
    return next((name for name, pattern in SOURCES.items() if pattern.match(url.strip())), None)


def _same_link(a: str, b: str) -> bool:
    return a.split("?")[0].rstrip("/") == b.split("?")[0].rstrip("/")


class ExploError(RuntimeError):
    pass


class Explo:
    def __init__(self, url: str, user: str, password: str, timeout: float = 60.0, transport=None):
        self.http = httpx.Client(base_url=url.rstrip("/"), timeout=timeout, transport=transport)
        self.user = user
        self.password = password
        self._csrf: str | None = None
        self._lock = threading.Lock()

    def _login(self) -> None:
        self._csrf = self.http.get("/api/ui/csrf").json()["csrf_token"]
        r = self.http.post(
            "/api/ui/login", data={"username": self.user, "password": self.password}, headers={"X-CSRF-Token": self._csrf}
        )
        if r.status_code in (401, 403):
            raise ExploError("Explo не принял логин и пароль из настроек Volna")
        r.raise_for_status()

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        with self._lock:
            if self._csrf is None:
                self._login()
            for attempt in range(2):
                headers = {"X-CSRF-Token": self._csrf} if method != "GET" else {}
                r = self.http.request(method, path, headers=headers, **kwargs)
                if r.status_code not in (401, 403) or attempt:
                    return r
                self._login()  # сессия истекла или Explo перезапустился
            return r

    def system_user(self) -> str:
        """Аккаунт Navidrome, у которого Explo сам создаёт плейлисты."""
        r = self._request("GET", "/api/ui/config")
        r.raise_for_status()
        return str(r.json().get("values", {}).get("SYSTEM_USERNAME", ""))

    def add_playlist(self, url: str) -> dict:
        """Завести плейлист по ссылке (или найти уже заведённый); Explo сразу достаёт список треков."""
        r = self._request("POST", "/api/ui/custom-playlists", json={"url": url, "source": source_of(url), "refresh_days": 0})
        if r.status_code == 409:
            listed = self._request("GET", "/api/ui/custom-playlists").json()
            found = next((p for p in listed if _same_link(p.get("source_url", ""), url)), None)
            if found is None:
                raise ExploError("Explo уже импортировал этот плейлист, но не нашёл его")
            return found
        if r.status_code >= 400:
            raise ExploError(f"Explo не принял ссылку: {r.text.strip()[:200]}")
        return r.json()

    def tracks(self, playlist_id: str) -> list[dict]:
        r = self._request("GET", "/api/ui/playlists", params={"type": playlist_id})
        if r.status_code == 404:
            return []
        r.raise_for_status()
        return r.json().get("tracks") or []

    def running(self) -> bool:
        r = self._request("GET", "/api/ui/run/status")
        r.raise_for_status()
        return bool(r.json().get("running"))

    def run(self, playlist_id: str) -> bool:
        """Докачать недостающее и обновить библиотеку. False — Explo занят другим запуском."""
        r = self._request(
            "POST", "/api/ui/run", data={"playlist": playlist_id, "download_mode": "normal", "replace_playlist": "true"}
        )
        if r.status_code == 409:
            return False
        if r.status_code >= 400:
            raise ExploError(f"Explo не запустил загрузку: {r.text.strip()[:200]}")
        return True
