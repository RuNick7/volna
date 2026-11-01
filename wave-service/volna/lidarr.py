"""Lidarr — список исполнителей, чьи дискографии докачивает Soularr из того же стека.

Отсюда любой пользователь может поставить исполнителя на докачку: со страницы артиста
(«Скачать дискографию») или найти в MusicBrainz того, кого в библиотеке ещё нет. Слежение
включается за всеми альбомами и за новыми релизами; какие альбомы считать (студийные,
без сборников), решает профиль метаданных Lidarr.
"""

from __future__ import annotations

import threading
import time

import httpx

from .library import skeleton

CACHE_SECONDS = 60


class LidarrError(RuntimeError):
    pass


class Lidarr:
    def __init__(self, url: str, api_key: str, timeout: float = 60.0, transport=None):
        self.http = httpx.Client(
            base_url=url.rstrip("/") + "/api/v1", headers={"X-Api-Key": api_key}, timeout=timeout, transport=transport
        )
        self._artists: list[dict] = []
        self._fetched = 0.0
        self._lock = threading.Lock()

    def _get(self, path: str, **params):
        r = self.http.get(path, params=params)
        r.raise_for_status()
        return r.json()

    def artists(self, fresh: bool = False) -> list[dict]:
        with self._lock:
            if fresh or time.time() - self._fetched > CACHE_SECONDS:
                self._artists = self._get("/artist")
                self._fetched = time.time()
            return self._artists

    def find(self, name: str) -> dict | None:
        """Исполнитель из библиотеки в Lidarr: имя с точностью до записи («Земфира» = «Zemfira»)."""
        target = skeleton(name)
        same = [a for a in self.artists() if skeleton(a["artistName"]) == target]
        return same[0] if len(same) == 1 else None

    def progress(self, artist_id: int) -> dict:
        albums = self._get("/album", artistId=artist_id)
        wanted = [a for a in albums if a.get("monitored")]
        have = sum(1 for a in wanted if (a.get("statistics") or {}).get("trackFileCount", 0) > 0)
        return {"albums": len(wanted), "have": have}

    def status(self, artist: dict | None) -> dict:
        if artist is None:
            return {"in_lidarr": False, "following": False}
        following = bool(artist.get("monitored")) and artist.get("monitorNewItems") == "all"
        return {"in_lidarr": True, "following": following, "name": artist["artistName"], **self.progress(artist["id"])}

    def search(self, term: str) -> list[dict]:
        """Исполнители MusicBrainz по имени — чтобы добавить и тех, кого в библиотеке ещё нет."""
        known = {a["foreignArtistId"]: a for a in self.artists()}
        found = []
        for a in self._get("/artist/lookup", term=term)[:20]:
            poster = next((i.get("remoteUrl") for i in a.get("images", []) if i.get("coverType") == "poster"), "")
            existing = known.get(a.get("foreignArtistId", ""))
            found.append({
                "mbid": a.get("foreignArtistId", ""),
                "name": a.get("artistName", ""),
                "disambiguation": a.get("disambiguation", ""),
                "type": a.get("artistType", ""),
                "overview": (a.get("overview") or "")[:300],
                "image": poster or next((i.get("remoteUrl") for i in a.get("images", [])), ""),
                "following": bool(existing and existing.get("monitored") and existing.get("monitorNewItems") == "all"),
            })
        return found

    def resolve(self, name: str) -> str:
        """MusicBrainz ID исполнителя из библиотеки; без угадывания между тёзками."""
        target = skeleton(name)
        same = [a for a in self._get("/artist/lookup", term=name) if skeleton(a.get("artistName", "")) == target]
        if len(same) > 1:
            raise LidarrError("В MusicBrainz несколько исполнителей с таким именем: найдите нужного в «Добавить исполнителя»")
        if not same:
            raise LidarrError("Не нашёл исполнителя в MusicBrainz под таким именем: попробуйте «Добавить исполнителя»")
        return same[0]["foreignArtistId"]

    def follow(self, mbid: str) -> dict:
        """Поставить исполнителя на докачку: добавить в Lidarr или включить слежение за всеми альбомами."""
        existing = next((a for a in self.artists(fresh=True) if a["foreignArtistId"] == mbid), None)
        if existing is None:
            found = self._get("/artist/lookup", term=f"lidarr:{mbid}")
            if not found:
                raise LidarrError("Lidarr не нашёл этого исполнителя в MusicBrainz")
            root = self._get("/rootfolder")[0]
            body = {
                **found[0],
                "qualityProfileId": root.get("defaultQualityProfileId") or 1,
                "metadataProfileId": root.get("defaultMetadataProfileId") or 1,
                "rootFolderPath": root["path"],
                "monitored": True,
                "monitorNewItems": "all",
                "addOptions": {"monitor": "all", "searchForMissingAlbums": False},  # ищет и качает Soularr
            }
            r = self.http.post("/artist", json=body)
            if r.status_code >= 400:
                raise LidarrError(f"Lidarr не добавил исполнителя: {r.text.strip()[:200]}")
            artist = r.json()
        else:
            artist = {**existing, "monitored": True, "monitorNewItems": "all"}
            self.http.put(f"/artist/{artist['id']}", json=artist).raise_for_status()
            albums = [a["id"] for a in self._get("/album", artistId=artist["id"]) if not a.get("monitored")]
            if albums:
                self.http.put("/album/monitor", json={"albumIds": albums, "monitored": True}).raise_for_status()
        self.artists(fresh=True)
        return artist
