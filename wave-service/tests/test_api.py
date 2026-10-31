from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from volna.app import create_app
from volna.config import Settings


@pytest.fixture()
def client(tmp_path):
    return TestClient(create_app(Settings(demo=True, db_path=tmp_path / "test.db")))


def test_one_button_flow(client):
    assert client.get("/api/health").json()["mode"] == "demo"

    state = client.post("/api/wave/start", json={}).json()
    track = state["track"]
    assert track and track["stream"].startswith("/api/stream/")
    assert len(state["upcoming"]) == 3
    sid = state["session"]

    state = client.post(f"/api/wave/{sid}/advance", json={"track_id": track["id"], "outcome": "skip", "position": 4}).json()
    assert state["track"]["id"] != track["id"]

    liked = client.post(f"/api/wave/{sid}/like", json={"track_id": state["track"]["id"]}).json()
    assert liked["track"]["liked"] is True

    switched = client.post(f"/api/wave/{sid}/mode", json={"mode": "discover"}).json()
    assert switched["mode"] == "discover"
    assert switched["track"]["id"] == state["track"]["id"]  # текущий трек не обрывается

    debug = client.get(f"/api/wave/{sid}/debug").json()
    assert debug["skipped"] == 1
    assert client.get("/api/profile").json()["clusters"]


def test_unknown_session_is_404(client):
    response = client.post("/api/wave/nope/advance", json={"track_id": "x", "outcome": "complete"})
    assert response.status_code == 404


def test_continue_playlist(client):
    seeds = [t["id"] for t in [client.post("/api/wave/start", json={}).json()["track"]]]
    state = client.post("/api/continue", json={"track_ids": seeds}).json()
    assert state["track"]["id"] not in seeds
    assert state["track"]["reason"].startswith("Похоже на плейлист")


def test_stream_supports_range_and_static_ui(client):
    track = client.post("/api/wave/start", json={}).json()["track"]
    partial = client.get(track["stream"], headers={"Range": "bytes=0-99"})
    assert partial.status_code == 206 and len(partial.content) == 100
    assert client.get(track["cover"]).headers["content-type"].startswith("image/svg")
    page = client.get("/")
    assert page.status_code == 200 and "Моя волна" in page.text
    assert client.get("/topo.js").status_code == 200
