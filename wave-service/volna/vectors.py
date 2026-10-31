"""Операции над эмбеддингами: нормировка, центрирование, сферический k-means."""

from __future__ import annotations

import numpy as np


def unit(x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, dtype=np.float32)
    norm = np.linalg.norm(x, axis=-1, keepdims=True)
    return x / np.maximum(norm, 1e-9)


class Space:
    """Центрированное пространство эмбеддингов.

    У сырых векторов MusiCNN почти все косинусы близки к 1, потому что у них
    общий положительный сдвиг. После вычитания среднего по библиотеке косинус
    начинает различать стили, и на нём можно строить вкус.
    """

    def __init__(self, mean: np.ndarray):
        self.mean = np.asarray(mean, dtype=np.float32)

    @classmethod
    def fit(cls, raw_vectors: list[np.ndarray]) -> "Space":
        return cls(np.mean(np.stack(raw_vectors), axis=0))

    def project(self, raw: np.ndarray) -> np.ndarray:
        return unit(np.asarray(raw, dtype=np.float32) - self.mean)


def kmeans(x: np.ndarray, weights: np.ndarray, k: int, iters: int = 40, seed: int = 0):
    """Взвешенный сферический k-means (x уже нормирован). Возвращает (centers, labels)."""
    rng = np.random.default_rng(seed)
    n = len(x)
    k = max(1, min(k, n))
    w = np.maximum(np.asarray(weights, dtype=np.float64), 1e-6)

    centers = [x[rng.choice(n, p=w / w.sum())]]
    for _ in range(1, k):
        nearest = np.max(x @ np.stack(centers).T, axis=1)
        d = np.clip(1.0 - nearest, 0.0, None) ** 2 * w
        if d.sum() <= 0:
            break
        centers.append(x[rng.choice(n, p=d / d.sum())])
    c = np.stack(centers).astype(np.float32)

    labels = np.full(n, -1)
    for _ in range(iters):
        new = np.argmax(x @ c.T, axis=1)
        if np.array_equal(new, labels):
            break
        labels = new
        for j in range(len(c)):
            members = labels == j
            if members.any():
                c[j] = unit((x[members] * w[members, None]).sum(axis=0))
    return c, labels
