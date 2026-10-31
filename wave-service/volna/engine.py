"""«Моя волна»: бесконечный поток, который подстраивается под пропуски.

Ключевые идеи:
* Якоря не двигаются. Кандидаты всегда ищутся от медоидов кластеров вкуса (или
  плейлиста), а не от последней песни, поэтому поток не «уплывает».
* Пропуск — главный сигнал. Ранний пропуск ослабляет кластер на всю сессию,
  отталкивает вектор сессии и выкидывает из очереди похожие треки, а два пропуска
  подряд переключают волну на другой кластер.
* Какой кластер играть дальше, решает Thompson sampling по статистике
  «дослушал / пропустил» с учётом времени суток.
"""

from __future__ import annotations

import logging
import secrets
import threading
import time
from collections import Counter, deque
from dataclasses import dataclass

import numpy as np

from .models import Candidate, Track
from .profile import DAY, TasteProfile, time_bucket
from .services import Deps
from .store import Event
from .vectors import unit

log = logging.getLogger(__name__)

EARLY_SKIP_SECONDS = 30.0
EARLY_SKIP_FRACTION = 0.25
COMPLETE_FRACTION = 0.8


@dataclass(frozen=True)
class Mode:
    name: str
    temperature: float  # разброс выдачи AudioMuse
    novelty: float  # бонус за незнакомые треки (минус значит «лучше знакомое»)
    explore: float  # шанс подмешать «разведку» из другого кластера
    recent_days: float  # не повторять треки, игравшие за эти дни
    favorites: int  # сколько уже любимых треков кластера подмешивать в кандидаты


MODES = {
    "favorite": Mode("favorite", 0.35, -0.3, 0.05, 2, 12),
    "balanced": Mode("balanced", 0.6, 0.15, 0.15, 3, 4),
    "discover": Mode("discover", 1.0, 0.7, 0.3, 30, 0),
}

# Веса слагаемых оценки «дослушает ли» (признаки нормируются внутри порции)
WEIGHTS = {
    "wave": dict(session=0.30, anchor=0.25, taste=0.15, negative=0.25, session_negative=0.35, artist=0.8, transition=0.2),
    "continue": dict(session=0.15, anchor=0.45, taste=0.20, negative=0.25, session_negative=0.35, artist=0.5, transition=0.2),
}

PITCH = {"C": 0, "C#": 1, "DB": 1, "D": 2, "D#": 3, "EB": 3, "E": 4, "F": 5, "F#": 6, "GB": 6,
         "G": 7, "G#": 8, "AB": 8, "A": 9, "A#": 10, "BB": 10, "B": 11}


def classify(outcome: str, position: float, duration: float) -> str:
    """complete | late_skip | early_skip | dislike | error."""
    if outcome in ("dislike", "error", "complete"):
        return outcome
    fraction = position / duration if duration > 0 else 0.0
    if fraction >= COMPLETE_FRACTION:
        return "complete"
    if position < EARLY_SKIP_SECONDS or fraction < EARLY_SKIP_FRACTION:
        return "early_skip"
    return "late_skip"


def _fifths(key: str, scale: str) -> int | None:
    pitch = PITCH.get((key or "").strip().upper())
    if pitch is None:
        return None
    if (scale or "").lower().startswith("min"):
        pitch = (pitch + 3) % 12  # параллельный мажор
    return (pitch * 7) % 12


def transition_cost(a: dict | None, b: dict | None) -> float:
    """Насколько резким будет переход: темп, энергия, тональность по квинтовому кругу."""
    if not a or not b:
        return 0.0
    cost = 0.0
    if a.get("tempo") and b.get("tempo"):
        cost += 0.4 * min(abs(a["tempo"] - b["tempo"]) / 40.0, 1.0)
    if a.get("energy") and b.get("energy"):
        cost += 0.3 * min(abs(a["energy"] - b["energy"]) / 0.05, 1.0)
    fa, fb = _fifths(a.get("key", ""), a.get("scale", "")), _fifths(b.get("key", ""), b.get("scale", ""))
    if fa is not None and fb is not None:
        d = abs(fa - fb)
        cost += 0.3 * min(d, 12 - d) / 6.0
    return cost


def _z(values: np.ndarray) -> np.ndarray:
    std = values.std()
    return (values - values.mean()) / std if std > 1e-6 else np.zeros_like(values)


@dataclass
class Pick:
    cluster: int
    score: float
    explore: bool
    reason: str


class WaveSession:
    BATCH = 5
    LOW_WATER = 3

    def __init__(
        self,
        deps: Deps,
        profile: TasteProfile,
        mode: str = "balanced",
        *,
        taste: TasteProfile | None = None,
        exclude: tuple[str, ...] | list[str] = (),
        kind: str = "wave",
        seed: int | None = None,
    ):
        self.id = secrets.token_urlsafe(8)
        self.deps = deps
        self.profile = profile  # откуда берём якоря: вкус целиком или один плейлист
        self.taste = taste or profile  # долгосрочный вкус для ранжирования
        self.mode = MODES.get(mode, MODES["balanced"])
        self.kind = kind
        self.weights = WEIGHTS[kind]
        self.rng = np.random.default_rng(seed)
        self.lock = threading.Lock()

        self.mult = np.ones(len(profile.clusters))
        self.successes = np.stack([c.successes for c in profile.clusters]).astype(float)
        self.failures = np.stack([c.failures for c in profile.clusters]).astype(float)
        self.session_vec: np.ndarray | None = None
        self.skipped: list[tuple[str, np.ndarray]] = []
        self.completed: list[str] = []
        self.shown: set[str] = set(exclude)
        self.recent_artists: deque[str] = deque(maxlen=4)
        self.queue: list[Track] = []
        self.current: Track | None = None
        self.picks: dict[str, Pick] = {}
        self.served: Counter[int] = Counter()  # сколько треков из каждого кластера уже выдано
        self.consecutive_skips = 0
        self.avoid: int | None = None
        self.touched = time.time()

    # --- публичный интерфейс ---

    def start(self) -> Track | None:
        return self._next()

    def advance(self, track_id: str, outcome: str, position: float = 0.0, duration: float = 0.0) -> Track | None:
        """Трек закончился или его пропустили: учесть реакцию и выдать следующий."""
        self.touched = time.time()
        track = self.current if self.current and self.current.id == track_id else self.taste.track_info.get(track_id)
        duration = duration or (track.duration if track else 0.0)
        kind = classify(outcome, position, duration)
        artist = track.artist if track else ""
        if kind != "error":
            self._learn(track_id, artist, kind, position, duration)
        if artist:
            self.recent_artists.append(artist.lower())
        return self._next()

    def like(self, track_id: str, liked: bool = True) -> None:
        self.touched = time.time()
        track = self.current if self.current and self.current.id == track_id else self.taste.track_info.get(track_id)
        artist = track.artist if track else ""
        v = self._vec(track_id)
        cluster = self._cluster_of(track_id, v)
        self._record(track_id, artist, "like" if liked else "unlike", 0.0, 0.0, cluster)
        try:
            (self.deps.library.star if liked else self.deps.library.unstar)(track_id)
        except Exception:  # noqa: BLE001
            log.warning("Не удалось поставить звезду %s в Navidrome", track_id, exc_info=True)
        if track:
            track.starred = liked
        if not liked:
            return
        self.taste.note(track_id, artist, "like")
        if cluster is not None:
            self.mult[cluster] = min(1.8, self.mult[cluster] * 1.15)
            self.successes[cluster, time_bucket(time.time())] += 1.5
        if v is not None:
            self.session_vec = v if self.session_vec is None else unit(0.55 * self.session_vec + 0.45 * v)

    def set_mode(self, mode: str) -> None:
        """Сменить характер волны: текущий трек играет дальше, очередь пересобирается."""
        self.mode = MODES.get(mode, self.mode)
        for t in self.queue:
            self.shown.discard(t.id)
        self.queue.clear()
        self._refill()

    def reason(self, track_id: str) -> str:
        pick = self.picks.get(track_id)
        return pick.reason if pick else ""

    def snapshot(self) -> dict:
        """Состояние сессии для отладки: как сместились веса кластеров."""
        return {
            "mode": self.mode.name,
            "kind": self.kind,
            "consecutive_skips": self.consecutive_skips,
            "completed": len(self.completed),
            "skipped": len(self.skipped),
            "clusters": [
                {
                    "label": c.label,
                    "share": round(c.share, 3),
                    "session_weight": round(float(self.mult[c.index]), 3),
                    "completes": round(float(self.successes[c.index].sum()), 2),
                    "skips": round(float(self.failures[c.index].sum()), 2),
                }
                for c in self.profile.clusters
            ],
        }

    # --- обучение на реакции ---

    def _learn(self, track_id: str, artist: str, kind: str, position: float, duration: float) -> None:
        v = self._vec(track_id)
        cluster = self._cluster_of(track_id, v)
        self._record(track_id, artist, kind, position, duration, cluster)
        self._sync(track_id, kind, position, duration)
        self.taste.note(track_id, artist, kind)
        bucket = time_bucket(time.time())

        if kind == "complete":
            self.consecutive_skips = 0
            if cluster is not None:
                if self.kind == "wave":  # в плейлисте дослушанное не должно вытеснять его другие части
                    self.mult[cluster] = min(1.5, self.mult[cluster] * 1.05)
                self.successes[cluster, bucket] += 1
            if v is not None:
                self.session_vec = v if self.session_vec is None else unit(0.7 * self.session_vec + 0.3 * v)
                self.completed.append(track_id)
            return

        if kind == "late_skip":
            if cluster is not None:
                self.mult[cluster] *= 0.85
                self.failures[cluster, bucket] += 0.4
            return

        # ранний пропуск или «не нравится»
        strong = kind == "dislike"
        self.consecutive_skips += 1
        if cluster is not None:
            self.mult[cluster] *= 0.4 if strong else 0.6
            self.failures[cluster, bucket] += 2 if strong else 1
            if self.consecutive_skips >= 2:
                self.mult[cluster] *= 0.5
                self.avoid = cluster
        if v is not None:
            self.skipped.append((track_id, v))
            if self.session_vec is not None:
                dot = float(self.session_vec @ v)
                if dot > 0:
                    self.session_vec = unit(self.session_vec - 0.6 * dot * v)
        self._purge(v, artist, cluster, threshold=0.45 if strong else 0.55)

    def _purge(self, v: np.ndarray | None, artist: str, cluster: int | None, threshold: float) -> None:
        """Убрать из очереди всё, что похоже на только что пропущенный трек."""
        if not self.queue:
            return
        vecs = self._vecs([t.id for t in self.queue])
        keep = []
        for t in self.queue:
            u = vecs.get(t.id)
            same_artist = bool(artist) and t.artist.lower() == artist.lower()
            too_close = v is not None and u is not None and float(u @ v) > threshold
            pick = self.picks.get(t.id)
            same_cluster = self.consecutive_skips >= 2 and pick is not None and pick.cluster == cluster
            if not (same_artist or too_close or same_cluster):
                keep.append(t)
        self.queue = keep

    def _record(self, track_id: str, artist: str, kind: str, position: float, duration: float, cluster: int | None) -> None:
        self.deps.store.add_event(
            Event(
                ts=time.time(), track_id=track_id, kind=kind, artist=artist, session=self.id,
                position=position, duration=duration, mode=self.mode.name, source=self.kind, cluster=cluster,
            )
        )

    def _sync(self, track_id: str, kind: str, position: float, duration: float) -> None:
        """Прослушивание и дизлайк уходят в Navidrome: их увидят все клиенты."""
        library = self.deps.library
        try:
            threshold = min(240.0, 0.5 * duration) if duration else 240.0
            if kind == "complete" or position >= threshold:
                library.scrobble(track_id, submission=True, time_ms=int(time.time() * 1000))
            if kind == "dislike":
                library.set_rating(track_id, 1)
        except Exception:  # noqa: BLE001
            log.warning("Не удалось синхронизировать %s с Navidrome", track_id, exc_info=True)

    # --- очередь ---

    def _next(self) -> Track | None:
        if len(self.queue) < self.LOW_WATER:
            self._refill()
        self.current = self.queue.pop(0) if self.queue else None
        if len(self.queue) < self.LOW_WATER:
            self._refill()
        if self.current:
            try:
                self.deps.library.scrobble(self.current.id, submission=False)
            except Exception:  # noqa: BLE001
                log.debug("now playing не отправлен", exc_info=True)
        return self.current

    def _refill(self) -> None:
        batch = self._batch(self.BATCH) or self._fallback(self.BATCH)
        self.queue.extend(batch)

    def _choose_clusters(self) -> list[tuple[int, bool]]:
        """Thompson sampling: (кластер, это разведка?)."""
        clusters = self.profile.clusters
        if len(clusters) == 1:
            return [(0, False)]
        share = np.array([c.share for c in clusters])
        served = np.array([self.served[c.index] for c in clusters], dtype=float)
        if self.kind == "continue":
            return self._choose_by_share(share, served)

        b = time_bucket(time.time())
        succ = self.successes[:, b] + 0.5 * (self.successes.sum(axis=1) - self.successes[:, b])
        fail = self.failures[:, b] + 0.5 * (self.failures.sum(axis=1) - self.failures[:, b])
        theta = self.rng.beta(1 + succ, 1 + fail)
        affinity = 1.0
        if self.session_vec is not None:
            affinity = 1 + 0.6 * np.clip(self.profile.centers @ self.session_vec, 0, None)
        satiety = np.exp(-0.07 * served)  # даже любимое приедается, если играет подряд
        p = theta * np.sqrt(share) * affinity * self.mult * satiety
        if self.avoid is not None:
            p[self.avoid] *= 0.05
            self.avoid = None
        order = np.argsort(-p)
        main = int(order[0])
        if self.rng.random() < self.mode.explore or self.consecutive_skips >= 2:
            others = order[1:]
            posterior = (1 + succ[others]) / (2 + succ[others] + fail[others])
            w = self.mult[others] * posterior / np.sqrt(share[others])
            return [(main, False), (int(self.rng.choice(others, p=w / w.sum())), True)]
        second = int(order[1])
        if p[second] >= 0.5 * p[main]:
            return [(main, False), (second, False)]
        return [(main, False)]

    def _choose_by_share(self, share: np.ndarray, served: np.ndarray) -> list[tuple[int, bool]]:
        """Продолжение плейлиста держит пропорции его частей; пропуски уменьшают долю части."""
        target = share * self.mult
        target /= target.sum()
        deficit = target - served / max(served.sum(), 1.0) + self.rng.normal(0, 0.02, len(share))
        order = np.argsort(-deficit)
        return [(int(order[0]), False), (int(order[1]), False)]

    def _seeds(self, cluster) -> list[str]:
        """Опорные треки для поиска. Каждый раз другие из ядра кластера: соседи одних
        и тех же медоидов быстро заканчиваются, а якорь при этом остаётся тем же кластером."""
        core = cluster.members[:15]
        if len(core) <= 3:
            return list(core)
        p = 1.0 / (np.arange(len(core)) + 3.0)
        return [str(s) for s in self.rng.choice(core, size=3, replace=False, p=p / p.sum())]

    def _candidates(self, c: int, n: int) -> dict[str, Candidate]:
        cluster = self.profile.clusters[c]
        seeds = self._seeds(cluster)
        add = list(seeds)
        if self.kind == "wave":
            # волна может подстроиться под настроение сессии, продолжение плейлиста — нет
            add += [i for i in reversed(self.completed) if i in self.picks and self.picks[i].cluster == c][:2]
        subtract = [i for i, _ in reversed(self.skipped) if i in self.picks and self.picks[i].cluster == c][:3]
        rows: list[Candidate] = []
        try:
            rows = self.deps.sim.alchemy(add, subtract, n=n, temperature=self.mode.temperature)
        except Exception:  # noqa: BLE001
            log.warning("Song Alchemy недоступен, ищу похожие по отдельным трекам", exc_info=True)
        if not rows:
            for seed in seeds:
                try:
                    rows += self.deps.sim.similar(seed, n=max(5, n // len(seeds)))
                except Exception:  # noqa: BLE001
                    log.warning("AudioMuse не нашёл похожих для %s", seed, exc_info=True)
        out = {r.id: r for r in rows}
        if self.mode.favorites:
            pool = [m for m in cluster.members[: self.mode.favorites * 3] if m not in self.shown]
            if pool:
                for m in self.rng.choice(pool, size=min(len(pool), self.mode.favorites), replace=False):
                    info = self.taste.track_info.get(str(m))
                    out.setdefault(str(m), Candidate(id=str(m), artist=info.artist if info else ""))
        return out

    def _batch(self, k: int) -> list[Track]:
        chosen = self._choose_clusters()
        quotas = {chosen[0][0]: k} if len(chosen) == 1 else {chosen[0][0]: k - k // 2, chosen[1][0]: k // 2}
        explore = {c for c, is_explore in chosen if is_explore}
        source: dict[str, int] = {}
        tracks: dict[str, Track] = {}
        vecs: dict[str, np.ndarray] = {}
        allowed: list[str] = []
        for attempt in (1, 3):  # если фильтры съели почти всё, просим у AudioMuse втрое больше
            for c, _ in chosen:
                for cid in self._candidates(c, n=max(20, 6 * quotas[c]) * attempt):
                    source.setdefault(cid, c)
            fresh = [i for i in source if i not in tracks and i not in self.shown and i not in self.taste.disliked]
            tracks.update(self.deps.library.songs(fresh))
            vecs.update(self._vecs(fresh))
            allowed += self._allowed(fresh, tracks, vecs)
            if len(allowed) >= 2 * k:
                break
        if not allowed:
            return []

        x = np.stack([vecs[i] for i in allowed])
        clusters = np.array([source[i] for i in allowed])
        scores = self._score(allowed, x, clusters, tracks)
        picked = self._select(allowed, x, scores, clusters, tracks, quotas, k)
        score_of = dict(zip(allowed, scores))
        batch = []
        for i in self._order(picked):
            c = source[i]
            self.shown.add(i)
            self.served[c] += 1
            self.picks[i] = Pick(cluster=c, score=float(score_of[i]), explore=c in explore, reason=self._reason(c, c in explore))
            batch.append(tracks[i])
        return batch

    def _allowed(self, ids: list[str], tracks: dict[str, Track], vecs: dict[str, np.ndarray]) -> list[str]:
        now = time.time()
        allowed = []
        for i in ids:
            t = tracks.get(i)
            if t is None or i not in vecs:
                continue
            if t.rating == 1:
                self.taste.disliked.add(i)
                continue
            if self.taste.track_skips.get(i, 0.0) >= 1.5:
                continue  # пропускал дважды за последнее время: пусть отдохнёт
            last = max(t.last_played or 0.0, self.taste.last_played.get(i, 0.0))
            if last and now - last < self.mode.recent_days * DAY:
                continue
            allowed.append(i)
        return allowed

    def _score(self, ids: list[str], x: np.ndarray, clusters: np.ndarray, tracks: dict[str, Track]) -> np.ndarray:
        """Оценка «дослушает ли»: близость к якорю, вкусу и сессии минус похожесть на пропущенное."""
        w = self.weights
        n = len(ids)
        f_anchor = np.einsum("nd,nd->n", x, self.profile.centers[clusters])
        f_taste = (x @ self.taste.centers.T).max(axis=1)
        neg = self.taste.negatives
        f_neg = (x @ neg.T).max(axis=1) if len(neg) else np.zeros(n)

        now = time.time()
        novelty = np.zeros(n)
        for j, i in enumerate(ids):
            t = tracks[i]
            heard = i in self.taste.known or t.play_count > 0 or t.starred
            last = max(t.last_played or 0.0, self.taste.last_played.get(i, 0.0))
            novelty[j] = 1.0 if not heard else 0.0 if last and now - last < 60 * DAY else 0.5
        artist = np.array([self.taste.artist_affinity(tracks[i].artist) for i in ids])

        transition = np.zeros(n)
        prev = self._last_id()
        if prev:
            feats = self.deps.vectors.features(ids + [prev])
            transition = np.array([transition_cost(feats.get(prev), feats.get(i)) for i in ids])

        anchor_weight = w["anchor"] + (0.0 if self.session_vec is not None else w["session"])
        score = (
            anchor_weight * _z(f_anchor)
            + w["taste"] * _z(f_taste)
            - w["negative"] * _z(f_neg)
            + self.mode.novelty * novelty
            + w["artist"] * artist
            - w["transition"] * transition
        )
        if self.session_vec is not None:
            score += w["session"] * _z(x @ self.session_vec)
        if self.skipped:
            recent = np.stack([v for _, v in self.skipped[-20:]])
            f_skip = (x @ recent.T).max(axis=1)
            score -= w["session_negative"] * _z(f_skip)
            score[f_skip > 0.8] -= 5.0  # почти копия того, что только что пропустили
        return score

    def _select(self, ids, x, scores, clusters, tracks, quotas, k) -> list[str]:
        """MMR: лучшие по оценке, но не похожие друг на друга и без повтора артистов."""
        blocked = set(self.recent_artists) | {t.artist.lower() for t in self.queue[-3:]}
        if self.current:
            blocked.add(self.current.artist.lower())
        chosen: list[int] = []
        per_cluster: Counter[int] = Counter()
        artists: set[str] = set()
        for strict in (True, False):
            while len(chosen) < k:
                best, best_value = None, -np.inf
                for n, i in enumerate(ids):
                    if n in chosen:
                        continue
                    artist = tracks[i].artist.lower()
                    if artist in artists:
                        continue
                    if strict and (per_cluster[int(clusters[n])] >= quotas.get(int(clusters[n]), 0) or artist in blocked):
                        continue
                    redundancy = max((float(x[n] @ x[m]) for m in chosen), default=0.0)
                    value = scores[n] - 0.6 * max(redundancy, 0.0)
                    if value > best_value:
                        best, best_value = n, value
                if best is None:
                    break
                chosen.append(best)
                per_cluster[int(clusters[best])] += 1
                artists.add(tracks[ids[best]].artist.lower())
        return [ids[n] for n in chosen]

    def _order(self, ids: list[str]) -> list[str]:
        """Жадно выстраиваем порядок, чтобы переходы по темпу и тональности были мягкими."""
        if len(ids) < 2:
            return ids
        prev = self._last_id()
        feats = self.deps.vectors.features(ids + ([prev] if prev else []))
        if not feats:
            return ids
        rest = list(ids)
        ordered: list[str] = []
        current = feats.get(prev) if prev else None
        if current is None:
            ordered.append(rest.pop(0))
            current = feats.get(ordered[0])
        while rest:
            nxt = min(rest, key=lambda i: transition_cost(current, feats.get(i)))
            rest.remove(nxt)
            ordered.append(nxt)
            current = feats.get(nxt)
        return ordered

    def _fallback(self, k: int) -> list[Track]:
        """Если похожих не нашлось совсем: случайное из библиотеки, лишь бы музыка не встала."""
        try:
            songs = self.deps.library.random_songs(40)
        except Exception:  # noqa: BLE001
            log.warning("Не удалось получить случайные треки", exc_info=True)
            return []
        vecs = self._vecs([t.id for t in songs])
        out = []
        for t in songs:
            if t.id in self.shown or t.id in self.taste.disliked or t.id not in vecs:
                continue
            self.shown.add(t.id)
            self.picks[t.id] = Pick(self.profile.nearest(vecs[t.id]), 0.0, True, "Случайное из библиотеки")
            out.append(t)
            if len(out) >= k:
                break
        return out

    # --- вспомогательное ---

    def _reason(self, c: int, explore: bool) -> str:
        label = self.profile.clusters[c].label
        if self.kind == "continue":
            return f"Похоже на плейлист: {label}" if label else "Похоже на плейлист"
        if explore:
            return f"Разведка: {label}" if label else "Разведка"
        return f"В духе: {label}" if label else "Моя волна"

    def _last_id(self) -> str | None:
        if self.queue:
            return self.queue[-1].id
        return self.current.id if self.current else None

    def _cluster_of(self, track_id: str, v: np.ndarray | None) -> int | None:
        if track_id in self.picks:
            return self.picks[track_id].cluster
        return self.profile.nearest(v) if v is not None else None

    def _vec(self, track_id: str) -> np.ndarray | None:
        return self._vecs([track_id]).get(track_id)

    def _vecs(self, ids: list[str]) -> dict[str, np.ndarray]:
        return {i: self.profile.space.project(v) for i, v in self.deps.vectors.get(ids).items()}
