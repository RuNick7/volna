from __future__ import annotations

from fastapi.testclient import TestClient

from volna.app import create_app
from volna.config import Settings


def make_client(tmp_path, auth=True):
    app = create_app(Settings(demo=True, db_path=tmp_path / "test.db", auth=auth))
    app.state.auth.verify = lambda user, password: (user, password) == ("owner", "secret")
    return TestClient(app)


def test_everything_is_open_without_auth(tmp_path):
    client = make_client(tmp_path, auth=False)
    assert client.get("/api/home").status_code == 200
    assert client.get("/api/auth/me").json() == {"auth": False, "user": None}


def test_api_requires_login(tmp_path):
    client = make_client(tmp_path)
    assert client.get("/api/home").status_code == 401
    assert client.get("/api/stream/x").status_code == 401

    health = client.get("/api/health").json()
    assert health == {"service": "volna", "mode": "demo", "version": health["version"], "auth": True}

    assert client.post("/api/auth/login", json={"user": "owner", "password": "wrong"}).status_code == 401
    token = client.post("/api/auth/login", json={"user": "owner", "password": "secret"}).json()["token"]

    # приложение шлёт заголовок, веб-версия — cookie, которую выставил вход
    assert client.get("/api/home").status_code == 200
    bare = TestClient(client.app)
    assert bare.get("/api/home", headers={"Authorization": f"Bearer {token}"}).status_code == 200
    assert bare.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"}).json()["user"] == "owner"
    assert "navidrome" in bare.get("/api/health", headers={"Authorization": f"Bearer {token}"}).json()

    bare.post("/api/auth/logout", headers={"Authorization": f"Bearer {token}"})
    assert bare.get("/api/home", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_tokens_survive_restart_and_cookie_follows_prefix(tmp_path):
    client = make_client(tmp_path)
    response = client.post(
        "/api/auth/login", json={"user": "owner", "password": "secret"}, headers={"X-Forwarded-Prefix": "/volna"}
    )
    assert "Path=/volna/" in response.headers["set-cookie"]
    assert "HttpOnly" in response.headers["set-cookie"]
    token = response.json()["token"]

    restarted = make_client(tmp_path)
    assert restarted.get("/api/home", headers={"Authorization": f"Bearer {token}"}).status_code == 200


def test_login_attempts_are_limited(tmp_path):
    client = make_client(tmp_path)
    for _ in range(5):
        assert client.post("/api/auth/login", json={"user": "owner", "password": "nope"}).status_code == 401
    assert client.post("/api/auth/login", json={"user": "owner", "password": "secret"}).status_code == 429


def test_only_the_owner_account_may_log_in(tmp_path, monkeypatch):
    import volna.navidrome as navidrome

    pinged = []
    monkeypatch.setattr(navidrome.Navidrome, "ping", lambda self: pinged.append(self.user) or True)
    app = create_app(Settings(demo=True, db_path=tmp_path / "test.db", auth=True))
    from volna.config import ServerConfig

    app.state.runtime.server = ServerConfig("http://navidrome:4533", "Owner", "pw")
    client = TestClient(app)
    guest = client.post("/api/auth/login", json={"user": "guest", "password": "x"})
    assert guest.status_code == 403 and "не подключён" in guest.json()["detail"]
    assert client.post("/api/auth/login", json={"user": "owner", "password": "x"}).status_code == 200
    assert pinged == ["owner"]  # чужой логин даже не проверяем в Navidrome
