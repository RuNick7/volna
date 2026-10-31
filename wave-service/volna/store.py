"""SQLite: события прослушивания и кэш эмбеддингов, признаков и метаданных треков."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .models import Track

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY,
    ts REAL NOT NULL,
    session TEXT,
    track_id TEXT NOT NULL,
    artist TEXT,
    kind TEXT NOT NULL,
    position REAL,
    duration REAL,
    mode TEXT,
    source TEXT,
    cluster INTEGER
);
CREATE INDEX IF NOT EXISTS events_ts ON events(ts);
CREATE TABLE IF NOT EXISTS embeddings (id TEXT PRIMARY KEY, vec BLOB, fetched_at REAL);
CREATE TABLE IF NOT EXISTS features (id TEXT PRIMARY KEY, data TEXT, fetched_at REAL);
CREATE TABLE IF NOT EXISTS tracks (id TEXT PRIMARY KEY, data TEXT, fetched_at REAL);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT);
"""

# Колонки, добавленные после первой версии базы: докатываются через ALTER TABLE
EVENT_EXTRA_COLUMNS = ("album_id", "artist_id", "context_type", "context_id", "device", "entity")
EVENT_COLUMNS = (
    "ts", "track_id", "kind", "artist", "session", "position", "duration", "mode", "source", "cluster",
) + EVENT_EXTRA_COLUMNS

CHUNK = 500


@dataclass
class Event:
    ts: float
    track_id: str  # id трека, а для лайков альбома или артиста — их id (см. entity)
    kind: str  # complete | late_skip | early_skip | dislike | like | unlike
    artist: str = ""
    session: str = ""
    position: float = 0.0
    duration: float = 0.0
    mode: str = ""  # характер волны
    source: str = ""  # откуда взялся трек: audiomuse | favorites | similar | random | manual
    cluster: int | None = None
    album_id: str = ""
    artist_id: str = ""
    context_type: str = ""  # где играл: wave | continue | album | playlist | artist | search | library
    context_id: str = ""
    device: str = ""
    entity: str = "track"  # track | album | artist


class Store:
    def __init__(self, path: Path | str):
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._lock = threading.Lock()
        with self._lock:
            self._db.executescript(SCHEMA)
            existing = {row[1] for row in self._db.execute("PRAGMA table_info(events)")}
            for column in EVENT_EXTRA_COLUMNS:
                if column not in existing:
                    self._db.execute(f"ALTER TABLE events ADD COLUMN {column} TEXT")
            self._db.commit()
        self._vec_cache: dict[str, np.ndarray] = {}

    def _rows(self, sql: str, ids: list[str]) -> list[tuple]:
        rows: list[tuple] = []
        with self._lock:
            for i in range(0, len(ids), CHUNK):
                part = ids[i : i + CHUNK]
                marks = ",".join("?" * len(part))
                rows += self._db.execute(sql.format(marks=marks), part).fetchall()
        return rows

    def _write(self, sql: str, rows: list[tuple]) -> None:
        if not rows:
            return
        with self._lock:
            self._db.executemany(sql, rows)
            self._db.commit()

    # --- события ---

    def add_event(self, e: Event) -> None:
        columns = ", ".join(EVENT_COLUMNS)
        marks = ", ".join("?" * len(EVENT_COLUMNS))
        self._write(
            f"INSERT INTO events ({columns}) VALUES ({marks})",
            [tuple(getattr(e, c) for c in EVENT_COLUMNS)],
        )

    def events(self, since: float = 0.0) -> list[Event]:
        with self._lock:
            rows = self._db.execute(
                f"SELECT {', '.join(EVENT_COLUMNS)} FROM events WHERE ts >= ? ORDER BY ts", (since,)
            ).fetchall()
        return [
            Event(**{c: v for c, v in zip(EVENT_COLUMNS, row) if v is not None or c == "cluster"})
            for row in rows
        ]

    def count_events(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM events").fetchone()[0]

    # --- настройки ---

    def setting(self, key: str, default=None):
        with self._lock:
            row = self._db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def put_setting(self, key: str, value) -> None:
        self._write(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
            [(key, json.dumps(value, ensure_ascii=False))],
        )

    # --- эмбеддинги ---

    def embeddings(self, ids: list[str], retry_after: float) -> tuple[dict[str, np.ndarray], list[str]]:
        """Возвращает найденные векторы и id, которые нужно (пере)запросить."""
        found = {i: self._vec_cache[i] for i in ids if i in self._vec_cache}
        rest = [i for i in ids if i not in found]
        known_missing: set[str] = set()
        now = time.time()
        for track_id, blob, fetched_at in self._rows(
            "SELECT id, vec, fetched_at FROM embeddings WHERE id IN ({marks})", rest
        ):
            if blob is not None:
                vec = np.frombuffer(blob, dtype=np.float32)
                self._vec_cache[track_id] = vec
                found[track_id] = vec
            elif now - fetched_at < retry_after:
                known_missing.add(track_id)
        missing = [i for i in rest if i not in found and i not in known_missing]
        return found, missing

    def put_embeddings(self, items: dict[str, np.ndarray | None]) -> None:
        now = time.time()
        rows = []
        for track_id, vec in items.items():
            if vec is not None:
                vec = np.asarray(vec, dtype=np.float32)
                self._vec_cache[track_id] = vec
            rows.append((track_id, None if vec is None else vec.tobytes(), now))
        self._write("INSERT OR REPLACE INTO embeddings (id, vec, fetched_at) VALUES (?, ?, ?)", rows)

    # --- признаки (tempo, energy, тональность) ---

    def features(self, ids: list[str], retry_after: float) -> tuple[dict[str, dict], list[str]]:
        found: dict[str, dict] = {}
        known_missing: set[str] = set()
        now = time.time()
        for track_id, data, fetched_at in self._rows(
            "SELECT id, data, fetched_at FROM features WHERE id IN ({marks})", ids
        ):
            if data is not None:
                found[track_id] = json.loads(data)
            elif now - fetched_at < retry_after:
                known_missing.add(track_id)
        return found, [i for i in ids if i not in found and i not in known_missing]

    def put_features(self, items: dict[str, dict | None]) -> None:
        now = time.time()
        rows = [(i, None if f is None else json.dumps(f), now) for i, f in items.items()]
        self._write("INSERT OR REPLACE INTO features (id, data, fetched_at) VALUES (?, ?, ?)", rows)

    # --- метаданные треков из Navidrome ---

    def tracks(self, ids: list[str], max_age: float) -> dict[str, Track]:
        now = time.time()
        return {
            track_id: Track.from_dict(json.loads(data))
            for track_id, data, fetched_at in self._rows(
                "SELECT id, data, fetched_at FROM tracks WHERE id IN ({marks})", ids
            )
            if now - fetched_at < max_age
        }

    def put_tracks(self, tracks: list[Track]) -> None:
        now = time.time()
        self._write(
            "INSERT OR REPLACE INTO tracks (id, data, fetched_at) VALUES (?, ?, ?)",
            [(t.id, json.dumps(t.to_dict(), ensure_ascii=False), now) for t in tracks],
        )
