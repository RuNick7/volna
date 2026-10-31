"""Клиент Navidrome (Subsonic / OpenSubsonic API)."""

from __future__ import annotations

import hashlib
import logging
import secrets
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import httpx

from .models import Media, Track
from .store import Store

log = logging.getLogger(__name__)

DAY = 86400.0
PASS_HEADERS = ("content-type", "content-length", "content-range", "accept-ranges")


class SubsonicError(RuntimeError):
    pass


def _iso_ts(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def parse_song(s: dict) -> Track:
    return Track(
        id=str(s["id"]),
        title=s.get("title", ""),
        artist=s.get("artist", ""),
        album=s.get("album", ""),
        duration=float(s.get("duration") or 0),
        artist_id=str(s.get("artistId", "")),
        album_id=str(s.get("albumId", "")),
        cover_id=str(s.get("coverArt", "")),
        play_count=int(s.get("playCount") or 0),
        last_played=_iso_ts(s.get("played")),
        starred=bool(s.get("starred")),
        rating=int(s.get("userRating") or 0),
    )


class Navidrome:
    API_VERSION = "1.16.1"

    def __init__(self, url: str, user: str, password: str, store: Store, timeout: float = 15.0):
        self.base = url.rstrip("/")
        self.user = user
        self.password = password
        self.store = store
        self.http = httpx.Client(timeout=timeout)
        self.pool = ThreadPoolExecutor(max_workers=8)

    def _auth(self) -> dict[str, str]:
        salt = secrets.token_hex(8)
        token = hashlib.md5((self.password + salt).encode()).hexdigest()
        return {"u": self.user, "t": token, "s": salt, "v": self.API_VERSION, "c": "volna", "f": "json"}

    def call(self, endpoint: str, **params) -> dict:
        query = {**self._auth(), **{k: v for k, v in params.items() if v is not None}}
        r = self.http.get(f"{self.base}/rest/{endpoint}", params=query)
        r.raise_for_status()
        body = r.json().get("subsonic-response", {})
        if body.get("status") != "ok":
            raise SubsonicError(body.get("error", {}).get("message", f"{endpoint} failed"))
        return body

    def ping(self) -> bool:
        self.call("ping")
        return True

    # --- история и вкус ---

    def starred_songs(self) -> list[Track]:
        songs = self.call("getStarred2").get("starred2", {}).get("song", [])
        return self._remember([parse_song(s) for s in songs])

    def frequent_songs(self, albums: int = 30) -> list[Track]:
        """Песни из самых часто и недавно слушаемых альбомов (с playCount)."""
        album_ids: list[str] = []
        for kind in ("frequent", "recent"):
            body = self.call("getAlbumList2", type=kind, size=albums)
            for album in body.get("albumList2", {}).get("album", []):
                if album["id"] not in album_ids:
                    album_ids.append(album["id"])

        def album_songs(album_id: str) -> list[Track]:
            try:
                songs = self.call("getAlbum", id=album_id).get("album", {}).get("song", [])
                return [parse_song(s) for s in songs]
            except (httpx.HTTPError, SubsonicError):
                log.warning("Не удалось загрузить альбом %s", album_id, exc_info=True)
                return []

        tracks = [t for chunk in self.pool.map(album_songs, album_ids) for t in chunk]
        return self._remember(tracks)

    def random_songs(self, n: int = 50) -> list[Track]:
        songs = self.call("getRandomSongs", size=min(n, 500)).get("randomSongs", {}).get("song", [])
        return self._remember([parse_song(s) for s in songs])

    def songs(self, ids: list[str]) -> dict[str, Track]:
        found = self.store.tracks(ids, max_age=DAY)
        missing = [i for i in ids if i not in found]

        def get(track_id: str) -> Track | None:
            try:
                return parse_song(self.call("getSong", id=track_id)["song"])
            except (httpx.HTTPError, SubsonicError, KeyError):
                log.warning("Не удалось загрузить трек %s", track_id, exc_info=True)
                return None

        fetched = [t for t in self.pool.map(get, missing) if t is not None]
        self.store.put_tracks(fetched)
        found.update({t.id: t for t in fetched})
        return found

    def _remember(self, tracks: list[Track]) -> list[Track]:
        self.store.put_tracks(tracks)
        return tracks

    # --- действия, которые синхронизируются со всеми клиентами ---

    def scrobble(self, track_id: str, submission: bool, time_ms: int | None = None) -> None:
        self.call("scrobble", id=track_id, submission=str(submission).lower(), time=time_ms)

    def star(self, track_id: str) -> None:
        self.call("star", id=track_id)

    def unstar(self, track_id: str) -> None:
        self.call("unstar", id=track_id)

    def set_rating(self, track_id: str, rating: int) -> None:
        self.call("setRating", id=track_id, rating=rating)

    # --- медиа ---

    def stream(self, track_id: str, range_header: str | None = None) -> Media:
        request = self.http.build_request(
            "GET",
            f"{self.base}/rest/stream",
            params={**self._auth(), "id": track_id},
            headers={"Range": range_header} if range_header else {},
        )
        resp = self.http.send(request, stream=True)
        headers = {k: v for k, v in resp.headers.items() if k.lower() in PASS_HEADERS}
        return Media(
            status=resp.status_code,
            media_type=resp.headers.get("content-type", "audio/mpeg"),
            headers=headers,
            body=resp.iter_bytes(),
            close=resp.close,
        )

    def cover(self, cover_id: str, size: int = 600) -> Media:
        r = self.http.get(f"{self.base}/rest/getCoverArt", params={**self._auth(), "id": cover_id, "size": size})
        return Media(
            status=r.status_code,
            media_type=r.headers.get("content-type", "image/jpeg"),
            headers={"Cache-Control": "public, max-age=86400"},
            body=[r.content],
        )
