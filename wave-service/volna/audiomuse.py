"""Клиент AudioMuse-AI REST (заголовок Authorization: Bearer <API_TOKEN>)."""

from __future__ import annotations

import httpx
import numpy as np

from .models import Candidate


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


class AudioMuse:
    def __init__(self, url: str, token: str = "", timeout: float = 20.0):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        self.http = httpx.Client(base_url=url.rstrip("/"), timeout=timeout, headers=headers)

    def ping(self) -> bool:
        # 404 значит «сервер доступен и токен принят, просто такого трека нет»
        r = self.http.get("/external/get_score", params={"id": "__volna_ping__"})
        if r.status_code in (401, 403):
            raise PermissionError("AudioMuse отклонил токен: проверьте AUDIOMUSE_TOKEN")
        return r.status_code in (200, 404)

    def alchemy(self, add: list[str], subtract: list[str] = (), n: int = 30, temperature: float = 0.6) -> list[Candidate]:
        """Поиск вокруг нескольких треков сразу, с вычитанием нежелательных."""
        items = [{"id": i, "op": "ADD", "type": "song"} for i in add]
        items += [{"id": i, "op": "SUBTRACT", "type": "song"} for i in subtract]
        r = self.http.post("/api/alchemy", json={"items": items, "n": n, "temperature": temperature})
        r.raise_for_status()
        return [c for c in map(_candidate, r.json().get("results") or []) if c]

    def similar(self, track_id: str, n: int = 20) -> list[Candidate]:
        r = self.http.get(
            "/api/similar_tracks", params={"item_id": track_id, "n": n, "eliminate_duplicates": "true"}
        )
        r.raise_for_status()
        data = r.json()
        rows = data if isinstance(data, list) else data.get("results") or []
        return [c for c in map(_candidate, rows) if c]

    def embedding(self, track_id: str) -> np.ndarray | None:
        r = self.http.get("/external/get_embedding", params={"id": track_id})
        if r.status_code == 404:
            return None
        r.raise_for_status()
        vec = r.json().get("embedding")
        return np.asarray(vec, dtype=np.float32) if vec else None

    def features(self, track_id: str) -> dict | None:
        r = self.http.get("/external/get_score", params={"id": track_id})
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
