from __future__ import annotations

from fastapi.testclient import TestClient

from volna.app import create_app
from volna.config import Settings
from volna.imports import Importer


class FakeExplo:
    def __init__(self, tracks, user="explo-account"):
        self._tracks = tracks
        self.user = user
        self.ran: list[str] = []

    def add_playlist(self, url):
        return {"id": "custom-1", "name": "Из Spotify", "artwork_url": ""}

    def tracks(self, playlist_id):
        return self._tracks

    def run(self, playlist_id):
        self.ran.append(playlist_id)
        return True

    def running(self):
        return False

    def system_user(self):
        return self.user


def make_client(tmp_path, explo=None):
    app = create_app(Settings(demo=True, db_path=tmp_path / "test.db"))
    app.state.importer = Importer(explo, poll=0.01, background=False) if explo else None
    return TestClient(app)


def test_import_matches_library_downloads_missing_and_creates_playlist(tmp_path):
    client = make_client(tmp_path)
    have = client.get("/api/library/recent").json()["tracks"][:2]
    tracks = [
        # как это пишет Spotify: другой регистр, «feat.» и «Remastered» в названии
        {"artist": have[0]["artist"].upper(), "title": f"{have[0]['title']} (feat. Someone)", "release": have[0]["album"]},
        {"artist": f"{have[1]['artist']}, Guest", "title": f"{have[1]['title']} - Музыка к фильму «Майор Гром»", "release": ""},
        {"artist": "Nobody Known", "title": "Not In Library", "release": ""},
    ]
    explo = FakeExplo(tracks)
    client = make_client(tmp_path, explo)

    job = client.post("/api/imports", json={"url": "https://open.spotify.com/playlist/37i9dQZF1?si=abc"}).json()
    assert job["status"] == "done", job["message"]
    assert (job["total"], job["found"], job["missing"]) == (3, 2, ["Nobody Known — Not In Library"])
    assert explo.ran == ["custom-1"]  # недостающее отдали Explo докачать
    page = client.get(f"/api/playlist/{job['playlist_id']}").json()
    assert [t["id"] for t in page["tracks"]] == [have[0]["id"], have[1]["id"]]

    listed = client.get("/api/imports").json()
    assert listed["available"] and listed["items"][0]["id"] == job["id"]


def test_import_without_missing_tracks_does_not_run_explo(tmp_path):
    client = make_client(tmp_path)
    have = client.get("/api/library/recent").json()["tracks"][0]
    explo = FakeExplo([{"artist": have["artist"], "title": have["title"], "release": have["album"]}])
    client = make_client(tmp_path, explo)
    job = client.post("/api/imports", json={"url": "https://open.spotify.com/album/1A2B3C"}).json()
    assert job["status"] == "done" and job["found"] == 1 and explo.ran == []


def test_import_needs_a_supported_link_and_explo(tmp_path):
    assert make_client(tmp_path).get("/api/imports").json() == {"available": False, "items": []}
    assert make_client(tmp_path).post("/api/imports", json={"url": "https://open.spotify.com/playlist/x"}).status_code == 503
    client = make_client(tmp_path, FakeExplo([]))
    assert client.post("/api/imports", json={"url": "https://example.com/playlist/1"}).status_code == 422
    failed = client.post("/api/imports", json={"url": "https://open.spotify.com/playlist/empty"}).json()
    assert failed["status"] == "failed" and "не нашёл треков" in failed["message"]
