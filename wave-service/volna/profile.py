"""Профиль вкуса: что слушатель любит (кластеры), что не любит (негативы), как реагирует.

Сигналы берутся из двух мест: из Navidrome (звёзды, play count, оценка 1★, всё это
общее для всех клиентов) и из собственных событий волны (дослушал, пропустил и на
какой секунде). Пропуски Subsonic не хранит, поэтому они живут в нашей базе.
"""

from __future__ import annotations

import math
import time
from collections import defaultdict
from dataclasses import dataclass, field

import numpy as np

from .models import Track
from .services import Deps
from .store import Event
from .vectors import Space, kmeans

DAY = 86400.0
BUCKETS = 4  # утро, день, вечер, ночь

# Вклад события в долгосрочный вес трека (дальше умножается на затухание)
EVENT_WEIGHTS = {"complete": 1.0, "like": 3.0, "late_skip": -0.4, "early_skip": -1.5, "dislike": -5.0}
POSITIVE = ("complete", "like")
NEGATIVE = ("early_skip", "dislike")


def decay(age_seconds: float, half_life_days: float) -> float:
    return 0.5 ** (max(age_seconds, 0.0) / (half_life_days * DAY))


def time_bucket(ts: float) -> int:
    hour = time.localtime(ts).tm_hour
    if 5 <= hour < 11:
        return 0
    if 11 <= hour < 17:
        return 1
    if 17 <= hour < 23:
        return 2
    return 3


@dataclass
class Cluster:
    index: int
    center: np.ndarray
    weight: float
    share: float
    members: list[str]  # отсортированы по близости к центру
    medoids: list[str]  # реальные треки у центра: ими и ищем похожее
    artists: list[str]
    successes: np.ndarray = field(default_factory=lambda: np.zeros(BUCKETS))
    failures: np.ndarray = field(default_factory=lambda: np.zeros(BUCKETS))

    @property
    def label(self) -> str:
        return ", ".join(self.artists[:3])

    @property
    def completion_rate(self) -> float | None:
        total = self.successes.sum() + self.failures.sum()
        return float(self.successes.sum() / total) if total else None


@dataclass
class TasteProfile:
    space: Space
    clusters: list[Cluster]
    negatives: np.ndarray  # (m, d), центрированные векторы нелюбимого
    disliked: set[str]
    known: set[str]  # всё, что уже звучало или лайкнуто
    last_played: dict[str, float]
    track_skips: dict[str, float]  # затухающее число ранних пропусков трека
    artist_stats: dict[str, list[float]]  # артист (lower) -> [дослушал, пропустил]
    track_info: dict[str, Track]
    cold_start: bool = False
    built_at: float = 0.0

    def __post_init__(self) -> None:
        self.centers = np.stack([c.center for c in self.clusters])

    def nearest(self, v: np.ndarray) -> int:
        return int(np.argmax(self.centers @ v))

    def artist_affinity(self, artist: str) -> float:
        """От -0.5 (всегда пропускает) до +0.5 (всегда дослушивает)."""
        done, skipped = self.artist_stats.get(artist.lower(), (0.0, 0.0))
        return (done + 1) / (done + skipped + 2) - 0.5

    def note(self, track_id: str, artist: str, kind: str) -> None:
        """Мгновенно учитывает событие сессии, не дожидаясь пересборки профиля."""
        stats = self.artist_stats.setdefault(artist.lower(), [0.0, 0.0])
        if kind in POSITIVE:
            stats[0] += 1
            self.last_played[track_id] = time.time()
        elif kind in NEGATIVE:
            stats[1] += 1
            self.track_skips[track_id] = self.track_skips.get(track_id, 0.0) + (2.0 if kind == "dislike" else 1.0)
        elif kind == "late_skip":
            self.last_played[track_id] = time.time()
        if kind == "dislike":
            self.disliked.add(track_id)
        self.known.add(track_id)


def _cluster_count(n: int) -> int:
    return int(min(10, max(1, round(math.sqrt(n / 3)))))


def build_clusters(ids: list[str], x: np.ndarray, weights: np.ndarray, info: dict[str, Track], k: int) -> list[Cluster]:
    centers, labels = kmeans(x, weights, k)
    total = float(weights.sum()) or 1.0
    clusters: list[Cluster] = []
    for j in range(len(centers)):
        idx = np.flatnonzero(labels == j)
        if not len(idx):
            continue
        closeness = x[idx] @ centers[j] + 0.03 * np.log1p(weights[idx])
        members = [ids[i] for i in idx[np.argsort(-closeness)]]
        by_artist: dict[str, float] = defaultdict(float)
        for i in idx:
            artist = info[ids[i]].artist if ids[i] in info else ""
            if artist:
                by_artist[artist] += float(weights[i])
        clusters.append(
            Cluster(
                index=len(clusters),
                center=centers[j],
                weight=float(weights[idx].sum()),
                share=float(weights[idx].sum()) / total,
                members=members,
                medoids=members[:3],
                artists=sorted(by_artist, key=by_artist.get, reverse=True),
            )
        )
    return clusters


def build_profile(deps: Deps, now: float | None = None, max_positive: int = 800) -> TasteProfile:
    now = now or time.time()
    weight: dict[str, float] = defaultdict(float)
    info: dict[str, Track] = {}
    known: set[str] = set()
    last_played: dict[str, float] = {}
    disliked: set[str] = set()

    for t in deps.library.starred_songs():
        info[t.id] = t
        weight[t.id] += 3.0
        known.add(t.id)
    for t in deps.library.frequent_songs():
        info.setdefault(t.id, t)
        if t.play_count > 0:
            age = now - t.last_played if t.last_played else 90 * DAY
            weight[t.id] += math.log1p(t.play_count) * decay(age, 120)
            known.add(t.id)
            if t.last_played:
                last_played[t.id] = t.last_played
    for t in info.values():
        if t.rating == 1:
            disliked.add(t.id)

    events: list[Event] = deps.store.events(since=now - 180 * DAY)
    artist_stats: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    track_skips: dict[str, float] = defaultdict(float)
    for e in events:
        d = decay(now - e.ts, 60)
        weight[e.track_id] += EVENT_WEIGHTS.get(e.kind, 0.0) * d
        known.add(e.track_id)
        if e.kind in ("complete", "late_skip"):
            last_played[e.track_id] = max(last_played.get(e.track_id, 0.0), e.ts)
        if e.artist:
            if e.kind in POSITIVE:
                artist_stats[e.artist.lower()][0] += d
            elif e.kind in NEGATIVE:
                artist_stats[e.artist.lower()][1] += d
        if e.kind in NEGATIVE:
            track_skips[e.track_id] += decay(now - e.ts, 30) * (2.0 if e.kind == "dislike" else 1.0)
        if e.kind == "dislike":
            disliked.add(e.track_id)

    positive = sorted((i for i, w in weight.items() if w > 0.2 and i not in disliked), key=weight.get, reverse=True)
    positive = positive[:max_positive]
    negative = [i for i, w in weight.items() if w < -0.5 or i in disliked][:300]
    random_tracks = deps.library.random_songs(200)
    for t in random_tracks:
        info.setdefault(t.id, t)
    event_ids = [e.track_id for e in events]
    raw = deps.vectors.get(positive + negative + event_ids + [t.id for t in random_tracks])
    if not raw:
        raise RuntimeError("Нет ни одного эмбеддинга: проверьте, что AudioMuse проанализировал библиотеку")
    space = Space.fit(list(raw.values()))

    positive = [i for i in positive if i in raw]
    cold = len(positive) < 3
    if cold:
        positive = [t.id for t in random_tracks if t.id in raw]
        for i in positive:
            weight[i] = 0.5
    ids = positive
    x = space.project(np.stack([raw[i] for i in ids]))
    w = np.array([weight[i] for i in ids])
    clusters = build_clusters(ids, x, w, info, _cluster_count(len(ids)))

    profile = TasteProfile(
        space=space,
        clusters=clusters,
        negatives=space.project(np.stack([raw[i] for i in negative if i in raw]))
        if any(i in raw for i in negative)
        else np.zeros((0, len(space.mean)), dtype=np.float32),
        disliked=disliked,
        known=known,
        last_played=last_played,
        track_skips=dict(track_skips),
        artist_stats=dict(artist_stats),
        track_info=info,
        cold_start=cold,
        built_at=now,
    )

    # Статистика «дослушал / пропустил» по кластерам и времени суток: её использует выбор кластера
    for e in events:
        if e.track_id not in raw or e.kind not in EVENT_WEIGHTS:
            continue
        c = profile.clusters[profile.nearest(space.project(raw[e.track_id]))]
        d = decay(now - e.ts, 45)
        b = time_bucket(e.ts)
        if e.kind in POSITIVE:
            c.successes[b] += d
        elif e.kind == "late_skip":
            c.failures[b] += 0.4 * d
        else:
            c.failures[b] += d
    return profile


def build_context_profile(deps: Deps, track_ids: list[str], taste: TasteProfile) -> TasteProfile:
    """Профиль одного плейлиста: несколько якорей вместо «последней песни»."""
    raw = deps.vectors.get(track_ids)
    ids = [i for i in track_ids if i in raw]
    if not ids:
        raise ValueError("Для треков плейлиста нет эмбеддингов в AudioMuse")
    info = dict(taste.track_info)
    info.update(deps.library.songs(ids))
    x = taste.space.project(np.stack([raw[i] for i in ids]))
    k = min(4, max(1, math.ceil(len(ids) / 8)))
    clusters = build_clusters(ids, x, np.ones(len(ids)), info, k)
    return TasteProfile(
        space=taste.space,
        clusters=clusters,
        negatives=taste.negatives,
        disliked=taste.disliked,
        known=taste.known,
        last_played=taste.last_played,
        track_skips=taste.track_skips,
        artist_stats=taste.artist_stats,
        track_info=info,
        built_at=time.time(),
    )
