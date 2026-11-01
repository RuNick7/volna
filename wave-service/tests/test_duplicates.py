from __future__ import annotations

from dataclasses import replace

from volna.library import LibraryIndex, duplicate_copies
from volna.models import Track
from volna.profile import build_profile
from volna.services import build_deps


def test_copies_of_one_song_point_to_the_album_track():
    album = [Track(id=f"a{i}", title=f"Song {i}", artist="Макс Корж", album_id="album", duration=200 + i) for i in range(10)]
    single = Track(id="single", title="Song 3 (feat. Guest)", artist="Max Korzh", album_id="single", duration=204.5)
    live = Track(id="live", title="Song 3 (Live)", artist="Макс Корж", album_id="live", duration=203)
    longer = Track(id="edit", title="Song 3", artist="Макс Корж", album_id="other", duration=260)
    copies = duplicate_copies([*album, single, live, longer])
    assert copies == {"single": "a3"}  # «(Live)» и версия другой длины — отдельные песни


def test_copies_are_hidden_in_lists_and_kept_out_of_the_wave(tmp_path):
    deps = build_deps(None, tmp_path)
    snapshot = deps.library.sync()
    original = next(t for t in snapshot.songs if t.album_id)
    copy = replace(original, id="copy-1", album_id="single-album", play_count=0, starred=True)
    snapshot.songs.append(copy)
    deps.library.tracks[copy.id] = copy  # демо-библиотека отдаёт и звучание копии

    index = LibraryIndex(snapshot)
    assert index.copies["copy-1"] == original.id
    found = index.search(original.title)["tracks"]
    assert original.id in {t["id"] for t in found} and "copy-1" not in {t["id"] for t in found}
    assert "copy-1" not in {t["id"] for t in index.artist_page(original.artist_id)["tracks"]}

    assert "copy-1" in build_profile(deps, snapshot).hidden
