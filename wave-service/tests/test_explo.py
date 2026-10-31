from __future__ import annotations

import httpx

from volna.explo import Explo, source_of


def test_source_detection():
    assert source_of("https://open.spotify.com/playlist/6Oqx1d3AIaY6i5IfP7Znxx?si=Fy0P") == "spotify"
    assert source_of("https://open.spotify.com/intl-ru/album/1A2B3C") == "spotify"
    assert source_of("https://music.apple.com/ru/playlist/chill/pl.u-123") == "apple"
    assert source_of("https://open.spotify.com/track/1A2B3C") is None
    assert source_of("https://example.com/playlist/1") is None


def test_login_csrf_relogin_and_already_imported():
    calls = []
    sessions = {"valid": False}

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.url.path == "/api/ui/csrf":
            return httpx.Response(200, json={"csrf_token": "tok"})
        if request.url.path == "/api/ui/login":
            assert request.headers["X-CSRF-Token"] == "tok" and b"username=u" in request.content
            sessions["valid"] = True
            return httpx.Response(200)
        if not sessions["valid"]:
            return httpx.Response(401)
        if request.url.path == "/api/ui/custom-playlists" and request.method == "POST":
            assert request.headers["X-CSRF-Token"] == "tok"
            return httpx.Response(409, text="already imported")
        if request.url.path == "/api/ui/custom-playlists":
            return httpx.Response(200, json=[{"id": "custom-7", "source_url": "https://open.spotify.com/playlist/AbC?si=old"}])
        if request.url.path == "/api/ui/run/status":
            return httpx.Response(200, json={"running": True})
        return httpx.Response(404)

    explo = Explo("http://explo", "u", "p", transport=httpx.MockTransport(handler))
    found = explo.add_playlist("https://open.spotify.com/playlist/AbC?si=new")
    assert found["id"] == "custom-7"  # ссылка та же, хвост ?si=… не важен

    sessions["valid"] = False  # Explo перезапустился: сессия пропала, входим заново
    assert explo.running() is True
    assert calls.count(("POST", "/api/ui/login")) == 2
