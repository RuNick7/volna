"""Симуляция слушателя на демо-библиотеке: учится ли волна на пропусках и не уплывает ли плейлист.

Запуск:  python scripts/simulate.py
"""

from __future__ import annotations

import sys
import tempfile
from collections import Counter
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from volna.demo import GENRES, DemoWorld  # noqa: E402
from volna.engine import WaveSession  # noqa: E402
from volna.profile import build_context_profile, build_profile  # noqa: E402
from volna.services import Deps, Vectors  # noqa: E402
from volna.store import Store  # noqa: E402

# Вероятность дослушать трек по жанрам. Джаз (2) слушатель раньше любил, а теперь
# пропускает: волна должна это заметить, хотя в истории Navidrome джаза много.
LISTENER = {0: 0.85, 1: 0.8, 2: 0.1, 3: 0.75, 4: 0.3, 5: 0.5, 6: 0.05, 7: 0.4, -1: 0.35}


def make_deps(seed: int = 7) -> tuple[DemoWorld, Deps]:
    world = DemoWorld(seed=seed)
    store = Store(Path(tempfile.mkdtemp()) / "sim.db")
    return world, Deps(library=world, sim=world, store=store, vectors=Vectors(world, store), demo=True)


def listen(world: DemoWorld, session: WaveSession, tracks: int, rng: np.random.Generator, listener: dict | None = None) -> list[tuple[int, bool]]:
    """Прогоняет tracks треков; возвращает (жанр, дослушал?) для каждого."""
    log = []
    track = session.start()
    for _ in range(tracks):
        if track is None:
            break
        genre = world.genre[track.id]
        done = rng.random() < (listener or LISTENER)[genre]
        if done:
            track = session.advance(track.id, "complete", track.duration, track.duration)
        else:
            track = session.advance(track.id, "skip", float(rng.uniform(3, 20)), track.duration)
        log.append((genre, done))
    return log


def wave_learning(sessions: int = 6, per_session: int = 25, seed: int = 1, listener: dict | None = None) -> list[dict]:
    world, deps = make_deps()
    rng = np.random.default_rng(seed)
    report = []
    for n in range(sessions):
        profile = build_profile(deps)  # между сессиями профиль пересобирается с учётом пропусков
        log = listen(world, WaveSession(deps, profile, "balanced", seed=seed + n), per_session, rng, listener)
        genres = Counter(g for g, _ in log)
        after_skip = [log[i + 1][0] == g for i, (g, done) in enumerate(log[:-1]) if not done]
        report.append(
            {
                "session": n + 1,
                "completion": sum(done for _, done in log) / len(log),
                "jazz_share": genres[2] / len(log),
                "same_after_skip": sum(after_skip) / len(after_skip) if after_skip else 0.0,
                "top": ", ".join(f"{GENRES[g] if g >= 0 else 'кроссовер'} {c}" for g, c in genres.most_common(3)),
            }
        )
    return report


def continuation_drift(length: int = 40, seed: int = 3) -> dict:
    """Плейлист из двух жанров: продолжение должно держать оба, а не только жанр последней песни."""
    world, deps = make_deps()
    rng = np.random.default_rng(seed)
    a = [i for i in world.ids if world.genre[i] == 0]
    b = [i for i in world.ids if world.genre[i] == 7]
    playlist = [x for pair in zip(rng.choice(a, 8, replace=False), rng.choice(b, 8, replace=False)) for x in pair]
    playlist = [str(i) for i in playlist]

    taste = build_profile(deps)
    context = build_context_profile(deps, playlist, taste)
    session = WaveSession(deps, context, "balanced", taste=taste, exclude=playlist, kind="continue", seed=seed)
    ours = []
    track = session.start()
    while track is not None and len(ours) < length:
        ours.append(world.genre[track.id])
        track = session.advance(track.id, "complete", track.duration, track.duration)

    # Типичное «радио по последней песне»: случайный из 5 ближайших к предыдущему треку
    naive, played, last = [], set(playlist), playlist[-1]
    while len(naive) < length:
        options = [c.id for c in world.similar(last, 30) if c.id not in played][:5]
        last = str(rng.choice(options))
        played.add(last)
        naive.append(world.genre[last])

    def summary(genres: list[int]) -> dict:
        in_playlist = [g in (0, 7) for g in genres]
        count = Counter(genres)
        return {
            "in_playlist": sum(in_playlist) / len(genres),
            "balance": min(count[0], count[7]) / max(count[0], count[7], 1),
            "last_10_in_playlist": sum(in_playlist[-10:]) / 10,
        }

    return {"volna": summary(ours), "last_song_radio": summary(naive)}


def settings_effect(n: int = 25, seeds: int = 3) -> list[dict]:
    """Как настройки волны меняют звучание и долю нового."""
    from volna.settings import WaveSettings, track_traits

    cases = [
        ("Без настроек", WaveSettings()),
        ("Настроение: спокойное", WaveSettings(mood="calm")),
        ("Настроение: бодрое", WaveSettings(mood="energetic")),
        ("Занятие: засыпаю", WaveSettings(activity="sleep")),
        ("Без слов", WaveSettings(no_vocals=True)),
        ("Характер: любимое", WaveSettings(character="favorite")),
        ("Характер: незнакомое", WaveSettings(character="discover")),
    ]
    rows = []
    for label, settings in cases:
        energy, instrumental, new = [], [], []
        for seed in range(seeds):
            world, deps = make_deps()
            session = WaveSession(deps, build_profile(deps), settings, seed=seed)
            track = session.start()
            for _ in range(n):
                traits = track_traits(world.features_map[track.id])
                energy.append(traits.get("energy", 0.5))
                instrumental.append(traits.get("instrumental", 0.5))
                new.append(not (track.play_count > 0 or track.starred))
                track = session.advance(track.id, "complete", track.duration, track.duration)
        rows.append({"label": label, "energy": np.mean(energy), "instrumental": np.mean(instrumental), "new": np.mean(new)})
    return rows


def main() -> None:
    print("1. Волна учится на пропусках (слушатель разлюбил джаз)\n")
    print(f"{'сессия':>6} {'дослушано':>10} {'доля джаза':>11} {'тот же жанр после скипа':>24}  что играло чаще всего")
    for row in wave_learning():
        print(
            f"{row['session']:>6} {row['completion']:>10.0%} {row['jazz_share']:>11.0%}"
            f" {row['same_after_skip']:>24.0%}  {row['top']}"
        )

    print("\n2. Продолжение плейлиста «Пост-рок + Неоклассика», 40 треков\n")
    drift = continuation_drift()
    print(f"{'':<22}{'в жанрах плейлиста':>20}{'баланс жанров':>15}{'последние 10':>14}")
    for name, label in (("volna", "Volna (якоря)"), ("last_song_radio", "Радио по последней")):
        s = drift[name]
        print(f"{label:<22}{s['in_playlist']:>20.0%}{s['balance']:>15.0%}{s['last_10_in_playlist']:>14.0%}")

    print("\n3. Настройки волны (энергия и «инструментальность» по шкале 0–1)\n")
    print(f"{'':<24}{'энергия':>9}{'без слов':>10}{'новое':>8}")
    for row in settings_effect():
        print(f"{row['label']:<24}{row['energy']:>9.2f}{row['instrumental']:>10.2f}{row['new']:>8.0%}")


if __name__ == "__main__":
    main()
