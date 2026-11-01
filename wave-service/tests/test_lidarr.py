from __future__ import annotations

import json

import httpx
from fastapi.testclient import TestClient

from volna.app import create_app
from volna.config import Settings
from volna.lidarr import Lidarr


class FakeLidarr:
    """Минимальный Lidarr: один исполнитель уже есть, но не отслеживается."""

    def __init__(self, lookup: dict[str, list[dict]]):
        self.artists = [{"id": 1, "artistName": "Земфира", "foreignArtistId": "z-mbid", "monitored": False, "monitorNewItems": "none"}]
        self.albums = {1: [{"id": 10, "monitored": False, "statistics": {"trackFileCount": 5}},
                           {"id": 11, "monitored": False, "statistics": {"trackFileCount": 0}}]}
        self.lookup = lookup
        self.added: list[dict] = []
        self.monitored_albums: list[int] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path, method = request.url.path.removeprefix("/api/v1"), request.method
        if path == "/artist" and method == "GET":
            return httpx.Response(200, json=self.artists)
        if path == "/artist" and method == "POST":
            body = json.loads(request.content)
            artist = {**body, "id": len(self.artists) + 1}
            self.artists.append(artist)
            self.albums[artist["id"]] = [{"id": 20, "monitored": True, "statistics": {"trackFileCount": 0}}]
            self.added.append(body)
            return httpx.Response(201, json=artist)
        if path.startswith("/artist/") and method == "PUT":
            body = json.loads(request.content)
            self.artists = [body if a["id"] == body["id"] else a for a in self.artists]
            return httpx.Response(202, json=body)
        if path == "/album":
            return httpx.Response(200, json=self.albums.get(int(request.url.params["artistId"]), []))
        if path == "/album/monitor":
            ids = json.loads(request.content)["albumIds"]
            self.monitored_albums += ids
            for albums in self.albums.values():
                for a in albums:
                    a["monitored"] = a["monitored"] or a["id"] in ids
            return httpx.Response(202)
        if path == "/artist/lookup":
            return httpx.Response(200, json=self.lookup.get(request.url.params["term"], []))
        if path == "/rootfolder":
            return httpx.Response(200, json=[{"path": "/mnt/music/lidarr", "defaultQualityProfileId": 3, "defaultMetadataProfileId": 1}])
        return httpx.Response(404)


def test_existing_artist_is_found_by_spelling_and_followed():
    fake = FakeLidarr({})
    lidarr = Lidarr("http://lidarr", "key", transport=httpx.MockTransport(fake))
    found = lidarr.find("Zemfira")
    assert found["id"] == 1 and lidarr.status(found) == {"in_lidarr": True, "following": False, "name": "Земфира", "albums": 0, "have": 0}
    lidarr.follow("z-mbid")
    assert fake.monitored_albums == [10, 11] and fake.artists[0]["monitorNewItems"] == "all"
    assert lidarr.status(lidarr.find("Zemfira")) == {"in_lidarr": True, "following": True, "name": "Земфира", "albums": 2, "have": 1}


def test_library_artist_gets_its_discography_from_the_artist_page(tmp_path):
    client = TestClient(create_app(Settings(demo=True, db_path=tmp_path / "test.db")))
    artist = client.get("/api/library/artists?limit=1").json()["items"][0]
    fake = FakeLidarr({artist["title"]: [{"artistName": artist["title"], "foreignArtistId": "new-mbid"}],
                       "lidarr:new-mbid": [{"artistName": artist["title"], "foreignArtistId": "new-mbid"}]})
    client.app.state.lidarr = Lidarr("http://lidarr", "key", transport=httpx.MockTransport(fake))

    assert client.get(f"/api/lidarr/artist/{artist['id']}").json() == {"available": True, "in_lidarr": False, "following": False}
    done = client.post(f"/api/lidarr/artist/{artist['id']}").json()
    assert done["following"] and done["albums"] == 1
    added = fake.added[0]
    assert added["monitorNewItems"] == "all" and added["addOptions"]["monitor"] == "all" and added["qualityProfileId"] == 3


def test_ambiguous_names_are_not_guessed(tmp_path):
    client = TestClient(create_app(Settings(demo=True, db_path=tmp_path / "test.db")))
    artist = client.get("/api/library/artists?limit=1").json()["items"][0]
    twins = [{"artistName": artist["title"], "foreignArtistId": "a"}, {"artistName": artist["title"], "foreignArtistId": "b"}]
    client.app.state.lidarr = Lidarr("http://lidarr", "key", transport=httpx.MockTransport(FakeLidarr({artist["title"]: twins})))
    response = client.post(f"/api/lidarr/artist/{artist['id']}")
    assert response.status_code == 409 and "несколько" in response.json()["detail"]
    assert client.get("/api/lidarr/search?q=x").json() == {"artists": []}
