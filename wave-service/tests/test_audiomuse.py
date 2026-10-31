from __future__ import annotations

import json

import httpx
import pytest

from volna.audiomuse import AudioMuse, AudioMuseAuthError


class FakeAudioMuse:
    """Ведёт себя как AudioMuse с включённой авторизацией."""

    def __init__(self, token="tok-123", user="me", password="pw"):
        self.token, self.user, self.password = token, user, password
        self.sessions: set[str] = set()
        self.logins = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/auth":
            data = json.loads(request.content)
            if (data.get("user"), data.get("password")) != (self.user, self.password):
                return httpx.Response(401, json={"error": "Invalid credentials"})
            self.logins += 1
            session = f"s{self.logins}"
            self.sessions.add(session)
            return httpx.Response(200, json={"status": "ok"}, headers={"set-cookie": f"audiomuse_jwt={session}; Path=/"})
        authed = request.headers.get("authorization") == f"Bearer {self.token}" or (
            request.headers.get("cookie", "").removeprefix("audiomuse_jwt=") in self.sessions
        )
        if not authed:
            if path.startswith("/api/"):
                return httpx.Response(401, json={"error": "unauthorized"})
            return httpx.Response(302, headers={"location": "https://am.test/login"})
        if path == "/external/get_score":
            return httpx.Response(404, json={"error": "not found"})
        if path == "/external/get_embedding":
            return httpx.Response(200, json={"item_id": request.url.params["id"], "embedding": [0.1, 0.2]})
        return httpx.Response(404)


def client(server, **kwargs):
    return AudioMuse("https://am.test", transport=httpx.MockTransport(server.handler), **kwargs)


def test_without_credentials_reports_login_required():
    with pytest.raises(AudioMuseAuthError, match="требует вход"):
        client(FakeAudioMuse()).ping()


def test_token_works():
    assert client(FakeAudioMuse(), token="tok-123").ping()
    with pytest.raises(AudioMuseAuthError, match="не принял API-токен"):
        client(FakeAudioMuse(), token="wrong").ping()


def test_login_and_password_instead_of_token():
    server = FakeAudioMuse()
    am = client(server, user="me", password="pw")
    assert am.auth_mode == "login" and am.ping()
    assert am.embedding("t1").tolist() == pytest.approx([0.1, 0.2])
    assert server.logins == 1  # одна сессия на все запросы

    server.sessions.clear()  # сессия истекла или AudioMuse перезапустился
    assert am.embedding("t2") is not None
    assert server.logins == 2  # клиент сам вошёл заново


def test_wrong_password():
    with pytest.raises(AudioMuseAuthError, match="неверный логин или пароль"):
        client(FakeAudioMuse(), user="me", password="nope").ping()
