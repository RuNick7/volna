from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

from simulate import LISTENER, continuation_drift, listen, make_deps  # noqa: E402

from volna.demo import DISLIKED_GENRE, LIKED_GENRES  # noqa: E402
from volna.engine import WaveSession, classify  # noqa: E402
from volna.profile import build_profile  # noqa: E402


@pytest.fixture()
def world_deps():
    return make_deps()


def test_classify_uses_position_and_fraction():
    assert classify("skip", 10, 200) == "early_skip"
    assert classify("skip", 40, 400) == "early_skip"  # 10 % трека
    assert classify("skip", 120, 200) == "late_skip"
    assert classify("skip", 170, 200) == "complete"  # 85 % — считаем дослушанным
    assert classify("complete", 0, 0) == "complete"
    assert classify("error", 5, 200) == "error"


def test_profile_reflects_history(world_deps):
    world, deps = world_deps
    profile = build_profile(deps)
    assert not profile.cold_start
    top = sorted(profile.clusters, key=lambda c: -c.share)[:3]
    genres = {world.genre[m] for c in top for m in c.members[:5]}
    assert genres <= set(LIKED_GENRES) | {-1}
    assert DISLIKED_GENRE not in genres


def test_tracks_rated_one_star_in_navidrome_never_play(world_deps):
    # Subsonic не умеет искать по оценке, поэтому 1★ отсеивается при загрузке кандидатов
    world, deps = world_deps
    profile = build_profile(deps)
    banned_artist = world.tracks[profile.clusters[0].members[0]].artist
    for t in world.tracks.values():
        if t.artist == banned_artist:
            t.rating = 1
    session = WaveSession(deps, profile, seed=5)
    played = [session.start()]
    for _ in range(30):
        played.append(session.advance(played[-1].id, "complete", played[-1].duration, played[-1].duration))
    assert banned_artist not in {t.artist for t in played}


def test_early_skip_purges_similar_and_weakens_cluster(world_deps):
    _, deps = world_deps
    session = WaveSession(deps, build_profile(deps), seed=0)
    track = session.start()
    cluster = session.picks[track.id].cluster
    before = session.mult[cluster]
    session.advance(track.id, "skip", position=5, duration=track.duration)
    assert session.mult[cluster] < before
    assert all(t.artist != track.artist for t in [session.current, *session.queue])
    assert len(session.queue) >= session.LOW_WATER


def test_two_skips_in_a_row_switch_cluster(world_deps):
    _, deps = world_deps
    session = WaveSession(deps, build_profile(deps), seed=1)
    track = session.start()
    first = session.picks[track.id].cluster
    for _ in range(2):
        cluster = session.picks[track.id].cluster
        track = session.advance(track.id, "skip", position=3, duration=track.duration)
    assert session.picks[track.id].cluster != cluster or cluster != first


def test_dislike_is_synced_and_never_repeats(world_deps):
    world, deps = world_deps
    session = WaveSession(deps, build_profile(deps), seed=2)
    track = session.start()
    session.advance(track.id, "dislike", position=8, duration=track.duration)
    assert world.tracks[track.id].rating == 1
    assert track.id in session.taste.disliked
    fresh = WaveSession(deps, build_profile(deps), seed=3)
    seen = [fresh.start()]
    for _ in range(20):
        seen.append(fresh.advance(seen[-1].id, "complete", seen[-1].duration, seen[-1].duration))
    assert track.id not in {t.id for t in seen}


def test_skips_persist_into_next_profile(world_deps):
    _, deps = world_deps
    session = WaveSession(deps, build_profile(deps), seed=4)
    track = session.start()
    session.advance(track.id, "skip", position=2, duration=track.duration)
    kinds = [e.kind for e in deps.store.events()]
    assert kinds == ["early_skip"]
    assert build_profile(deps).track_skips[track.id] > 0


def test_wave_drops_genre_the_listener_now_skips():
    turned = dict(LISTENER)
    turned[1] = 0.05  # раньше любил Synthwave, теперь пропускает
    early, late = [], []
    for seed in range(6):
        world, deps = make_deps()
        log = listen(world, WaveSession(deps, build_profile(deps), seed=seed), 30, np.random.default_rng(seed), turned)
        genres = [g for g, _ in log]
        early.append(np.mean([g == 1 for g in genres[:10]]))
        late.append(np.mean([g == 1 for g in genres[10:]]))
    assert np.mean(late) < np.mean(early)
    assert np.mean(late) <= 0.08


def test_continuation_keeps_whole_playlist_not_last_song():
    results = [continuation_drift(seed=s) for s in range(4)]
    ours = np.mean([r["volna"]["balance"] for r in results])
    naive = np.mean([r["last_song_radio"]["balance"] for r in results])
    assert np.mean([r["volna"]["in_playlist"] for r in results]) >= 0.9
    assert ours >= 0.6
    assert ours > naive + 0.3
