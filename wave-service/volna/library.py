"""Запросы к синхронизированной библиотеке: полки главной, страницы альбома, артиста и
плейлиста, поиск. Всё считается по снимку в памяти, без лишних запросов к Navidrome."""

from __future__ import annotations

import random
import time
from collections import defaultdict
from datetime import date

from .models import Entity, LibrarySnapshot, Playlist, Track

DAY = 86400.0
FORGOTTEN_AFTER = 60 * DAY


def normalize(text: str) -> str:
    return text.casefold().replace("ё", "е")


def cover_url(cover_id: str) -> str:
    return f"/api/cover/{cover_id}" if cover_id else ""


def track_json(t: Track, reason: str = "") -> dict:
    return {
        "id": t.id,
        "title": t.title,
        "artist": t.artist,
        "album": t.album,
        "album_id": t.album_id,
        "artist_id": t.artist_id,
        "duration": t.duration,
        "year": t.year,
        "track_no": t.track_no,
        "liked": t.starred,
        "stream": f"/api/stream/{t.id}",
        "cover": cover_url(t.cover_id or t.album_id or t.id),
        "reason": reason,
    }


class LibraryIndex:
    def __init__(self, snapshot: LibrarySnapshot):
        self.snapshot = snapshot
        self.songs = {t.id: t for t in snapshot.songs}
        self.albums = {a.id: a for a in snapshot.albums}
        self.artists = {a.id: a for a in snapshot.artists}
        self.playlists = {p.id: p for p in snapshot.playlists}
        self.by_album: dict[str, list[Track]] = defaultdict(list)
        self.by_artist: dict[str, list[Track]] = defaultdict(list)
        for t in snapshot.songs:
            self.by_album[t.album_id].append(t)
            self.by_artist[t.artist_id].append(t)
        for tracks in self.by_album.values():
            tracks.sort(key=lambda t: (t.disc, t.track_no, normalize(t.title)))
        self.album_plays = {aid: sum(t.play_count for t in ts) for aid, ts in self.by_album.items()}
        self.album_last = {aid: max((t.last_played or 0.0) for t in ts) for aid, ts in self.by_album.items()}
        self.artist_plays = {aid: sum(t.play_count for t in ts) for aid, ts in self.by_artist.items()}
        self.albums_by_artist: dict[str, list[Entity]] = defaultdict(list)
        for a in snapshot.albums:
            artist_id = a.artist_id or (self.by_album[a.id][0].artist_id if self.by_album.get(a.id) else "")
            self.albums_by_artist[artist_id].append(a)

    # --- сериализация ---

    def album_cover(self, a: Entity) -> str:
        tracks = self.by_album.get(a.id)
        return cover_url(a.cover_id or (tracks[0].cover_id if tracks else "") or a.id)

    def album_json(self, a: Entity) -> dict:
        return {
            "type": "album", "id": a.id, "title": a.name, "subtitle": a.artist,
            "artist_id": a.artist_id or (self.by_album[a.id][0].artist_id if self.by_album.get(a.id) else ""),
            "year": a.year, "cover": self.album_cover(a), "liked": a.starred,
            "count": a.song_count or len(self.by_album.get(a.id, [])),
        }

    def artist_json(self, a: Entity) -> dict:
        albums = self.albums_by_artist.get(a.id, [])
        count = len(albums) or a.album_count
        if a.cover_id:
            cover = cover_url(a.cover_id)
        else:
            cover = self.album_cover(albums[0]) if albums else cover_url(a.id)
        return {"type": "artist", "id": a.id, "title": a.name, "subtitle": _albums_label(count),
                "liked": a.starred, "count": count, "cover": cover}

    def playlist_json(self, p: Playlist) -> dict:
        first = next((self.songs[i] for i in p.track_ids if i in self.songs), None)
        return {
            "type": "playlist", "id": p.id, "title": p.name, "subtitle": _tracks_label(len(p.track_ids)),
            "cover": cover_url(first.cover_id or first.album_id) if first else "", "count": len(p.track_ids),
        }

    # --- списки коллекции ---

    def albums_sorted(self, sort: str = "alpha", offset: int = 0, limit: int = 60) -> list[dict]:
        albums = list(self.snapshot.albums)
        if sort == "recent":  # недавно слушали
            albums = sorted((a for a in albums if self.album_last.get(a.id)), key=lambda a: -self.album_last[a.id])
        elif sort == "frequent":
            albums = sorted((a for a in albums if self.album_plays.get(a.id)), key=lambda a: -self.album_plays[a.id])
        elif sort == "newest":  # недавно добавлены
            albums.sort(key=lambda a: -(a.created or 0))
        elif sort == "liked":
            albums = [a for a in albums if a.starred]
        elif sort == "year":
            albums.sort(key=lambda a: (-a.year, normalize(a.name)))
        else:
            albums.sort(key=lambda a: normalize(a.name))
        return [self.album_json(a) for a in albums[offset : offset + limit]]

    def artists_sorted(self, sort: str = "alpha", offset: int = 0, limit: int = 100) -> list[dict]:
        artists = [a for a in self.snapshot.artists if self.by_artist.get(a.id) or a.album_count]
        if sort == "frequent":
            artists.sort(key=lambda a: -self.artist_plays.get(a.id, 0))
        elif sort == "liked":
            artists = [a for a in artists if a.starred]
        else:
            artists.sort(key=lambda a: normalize(a.name))
        return [self.artist_json(a) for a in artists[offset : offset + limit]]

    def playlists_list(self) -> list[dict]:
        return [self.playlist_json(p) for p in self.snapshot.playlists]

    def liked_tracks(self) -> list[Track]:
        return sorted((t for t in self.snapshot.songs if t.starred), key=lambda t: -(t.starred_at or 0))

    def recent_tracks(self, limit: int = 50) -> list[Track]:
        played = [t for t in self.snapshot.songs if t.last_played]
        return sorted(played, key=lambda t: -(t.last_played or 0))[:limit]

    def forgotten_tracks(self, limit: int = 20) -> list[Track]:
        """Отмеченное или заслушанное раньше, что давно не звучало (как «Тайник» у Яндекса)."""
        now = time.time()
        pool = [
            t for t in self.snapshot.songs
            if (t.starred or t.play_count >= 3) and t.rating != 1 and now - (t.last_played or 0) > FORGOTTEN_AFTER
        ]
        random.Random(date.today().toordinal()).shuffle(pool)  # своя подборка на каждый день
        return pool[:limit]

    # --- страницы ---

    def album_page(self, album_id: str) -> dict | None:
        album = self.albums.get(album_id)
        tracks = self.by_album.get(album_id, [])
        if album is None and not tracks:
            return None
        if album is None:
            first = tracks[0]
            album = Entity(album_id, first.album, artist=first.artist, artist_id=first.artist_id, year=first.year)
        duration = sum(t.duration for t in tracks)
        return {**self.album_json(album), "duration": duration, "tracks": [track_json(t) for t in tracks]}

    def artist_page(self, artist_id: str) -> dict | None:
        artist = self.artists.get(artist_id)
        tracks = self.by_artist.get(artist_id, [])
        if artist is None and not tracks:
            return None
        if artist is None:
            artist = Entity(artist_id, tracks[0].artist)
        popular = sorted(tracks, key=lambda t: (-t.play_count, -(t.last_played or 0), normalize(t.title)))
        albums = sorted(self.albums_by_artist.get(artist_id, []), key=lambda a: -a.year)
        return {
            **self.artist_json(artist),
            "top": [track_json(t) for t in popular[:10]],
            "tracks": [track_json(t) for t in popular],  # все треки: в приложении раскрываются по кнопке
            "albums": [self.album_json(a) for a in albums],
            "plays": self.artist_plays.get(artist_id, 0),
        }

    def playlist_page(self, playlist_id: str) -> dict | None:
        playlist = self.playlists.get(playlist_id)
        if playlist is None:
            return None
        tracks = [self.songs[i] for i in playlist.track_ids if i in self.songs]
        return {
            **self.playlist_json(playlist),
            "duration": sum(t.duration for t in tracks),
            "tracks": [track_json(t) for t in tracks],
        }

    # --- поиск и главная ---

    def search(self, query: str) -> dict:
        q = normalize(query.strip())
        if not q:
            return {"tracks": [], "albums": [], "artists": [], "playlists": []}

        def rank(name: str) -> int:
            n = normalize(name)
            return 0 if n == q else 1 if n.startswith(q) else 2 if q in n else 3

        tracks = sorted(
            (t for t in self.snapshot.songs if q in normalize(f"{t.title} {t.artist} {t.album}")),
            key=lambda t: (rank(t.title), -t.play_count),
        )[:30]
        albums = sorted((a for a in self.snapshot.albums if q in normalize(f"{a.name} {a.artist}")),
                        key=lambda a: (rank(a.name), -self.album_plays.get(a.id, 0)))[:12]
        artists = sorted((a for a in self.snapshot.artists if q in normalize(a.name)),
                         key=lambda a: (rank(a.name), -self.artist_plays.get(a.id, 0)))[:12]
        playlists = [p for p in self.snapshot.playlists if q in normalize(p.name)][:8]
        return {
            "tracks": [track_json(t) for t in tracks],
            "albums": [self.album_json(a) for a in albums],
            "artists": [self.artist_json(a) for a in artists],
            "playlists": [self.playlist_json(p) for p in playlists],
        }

    def home(self) -> list[dict]:
        """Полки главной: что важно открыть одним касанием, кроме самой волны."""
        artists = [a for a in self.snapshot.artists if a.starred]
        artists += sorted(
            (a for a in self.snapshot.artists if not a.starred and self.artist_plays.get(a.id)),
            key=lambda a: -self.artist_plays[a.id],
        )
        shelves = [
            ("playlists", "Волна по вашим плейлистам", self.playlists_list()[:12]),
            ("recent", "Недавно слушали", self.albums_sorted("recent", limit=12)),
            ("forgotten", "Давно не слушали", [track_json(t, "Давно не звучало") for t in self.forgotten_tracks(16)]),
            ("newest", "Новое в библиотеке", self.albums_sorted("newest", limit=12)),
            ("frequent", "Часто слушаете", self.albums_sorted("frequent", limit=12)),
            ("artists", "Ваши артисты", [self.artist_json(a) for a in artists[:12]]),
        ]
        return [{"id": key, "title": title, "items": items} for key, title, items in shelves if items]

    def stats(self) -> dict:
        return {
            "tracks": len(self.snapshot.songs),
            "albums": len(self.snapshot.albums),
            "artists": len(self.snapshot.artists),
            "playlists": len(self.snapshot.playlists),
            "liked": sum(1 for t in self.snapshot.songs if t.starred),
            "synced_at": self.snapshot.synced_at,
        }

    def add_playlist(self, playlist: Playlist) -> None:
        """Новый плейлист сразу виден в коллекции, не дожидаясь синхронизации."""
        self.snapshot.playlists.append(playlist)
        self.playlists[playlist.id] = playlist

    def set_starred(self, item_id: str, entity: str, liked: bool) -> None:
        """Отметка сразу видна в интерфейсе, не дожидаясь следующей синхронизации."""
        target = {"track": self.songs, "album": self.albums, "artist": self.artists}[entity].get(item_id)
        if target is not None:
            target.starred = liked
            if entity == "track":
                target.starred_at = time.time() if liked else None


def _plural(n: int, one: str, few: str, many: str) -> str:
    if n % 10 == 1 and n % 100 != 11:
        return one
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return few
    return many


def _tracks_label(n: int) -> str:
    return f"{n} {_plural(n, 'трек', 'трека', 'треков')}"


def _albums_label(n: int) -> str:
    return f"{n} {_plural(n, 'альбом', 'альбома', 'альбомов')}" if n else ""
