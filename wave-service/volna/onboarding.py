"""Первый вход, как в Яндекс Музыке: выбрать любимых артистов и сказать, как вам слушается.

Выбранным артистам ставится звезда в Navidrome — профиль вкуса уже учитывает отмеченных
артистов, так что первая волна сразу строится вокруг них. Пока человек выбирает, рядом с
отмеченным артистом появляются похожие по звучанию: у каждого артиста есть «центр» —
средний вектор его треков от AudioMuse.
"""

from __future__ import annotations

import numpy as np

from .library import LibraryIndex, skeleton
from .services import Vectors

ONBOARDED_KEY = "onboarded"
CHOICES = 60


def artist_choices(index: LibraryIndex, limit: int = CHOICES) -> list[dict]:
    """С кого начать выбор: уже отмеченные, потом кого больше слушали, потом у кого больше треков."""
    artists = [a for a in index.snapshot.artists if index.by_artist.get(a.id)]
    artists.sort(key=lambda a: (not a.starred, -index.artist_plays.get(a.id, 0), -len(index.by_artist[a.id]), a.name.casefold()))
    return [index.artist_json(a) for a in artists[:limit]]


class ArtistMap:
    """Артисты как точки в пространстве звучания библиотеки."""

    def __init__(self, index: LibraryIndex, vectors: Vectors):
        self.index = index
        songs = [t for tracks in index.by_artist.values() for t in tracks]
        found = vectors.get([t.id for t in songs])
        ids = [t.id for t in songs if t.id in found]
        if not ids:
            self.ids, self.centers = [], np.zeros((0, 1))
            return
        x = np.stack([found[i] for i in ids]).astype(np.float32)
        x -= x.mean(axis=0)  # без общего «звучания библиотеки» артисты различаются заметнее
        x /= np.linalg.norm(x, axis=1, keepdims=True) + 1e-9
        position = {track_id: row for row, track_id in enumerate(ids)}
        self.ids, centers = [], []
        for artist_id, tracks in index.by_artist.items():
            rows = [position[t.id] for t in tracks if t.id in position]
            if rows:
                c = x[rows].mean(axis=0)
                self.ids.append(artist_id)
                centers.append(c / (np.linalg.norm(c) + 1e-9))
        self.centers = np.stack(centers)
        self.row = {artist_id: i for i, artist_id in enumerate(self.ids)}

    def similar(self, artist_id: str, n: int = 6) -> list[str]:
        row = self.row.get(artist_id) if self.ids else None
        if row is None:
            return []
        scores = self.centers @ self.centers[row]
        scores[row] = -np.inf
        # тот же артист под другим написанием в тегах («Земфира», «Zemfira») — не «похожий»
        own = self._name(artist_id)
        picked = []
        for i in np.argsort(-scores):
            if self._name(self.ids[i]) != own:
                picked.append(self.ids[i])
            if len(picked) == n:
                break
        return picked

    def _name(self, artist_id: str) -> str:
        artist = self.index.artists.get(artist_id)
        return skeleton(artist.name) if artist else artist_id
