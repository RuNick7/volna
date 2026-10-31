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


def test_any_navidrome_user_gets_own_space(tmp_path, monkeypatch):
    import volna.app as app_module
    import volna.navidrome as navidrome
    from volna.config import ServerConfig
    from volna.demo import DemoWorld
    from volna.services import Deps
    from volna.store import Store

    accounts = {"owner": "pw-owner", "guest": "pw-guest"}

    def ping(self):
        if accounts.get(self.user.lower()) != self.password:  # Navidrome не различает регистр логина
            raise navidrome.SubsonicError("Wrong username or password")
        return True

    monkeypatch.setattr(navidrome.Navidrome, "ping", ping)
    monkeypatch.setattr(navidrome.Navidrome, "is_admin", lambda self: False)
    built = []

    def fake_user_deps(server, shared, data_dir):
        built.append((server.navidrome_user, server.navidrome_password))
        world = DemoWorld()
        return Deps(library=world, sim=shared.sim, store=Store(data_dir / f"user-{server.navidrome_user}.db"),
                    vectors=shared.vectors, demo=True)

    monkeypatch.setattr(app_module, "user_deps", fake_user_deps)
    app = create_app(Settings(demo=True, db_path=tmp_path / "test.db", auth=True))
    app.state.runtime.server = ServerConfig("http://navidrome:4533", "owner", "pw-owner")
    client = TestClient(app)

    def login(user, password):
        response = client.post("/api/auth/login", json={"user": user, "password": password})
        return response.status_code, {"Authorization": f"Bearer {response.json().get('token', '')}"}

    assert login("guest", "wrong")[0] == 401
    assert login("stranger", "pw-guest")[0] == 401
    status, owner = login("owner", "pw-owner")
    assert status == 200
    status, guest = login("Guest", "pw-guest")  # регистр логина не важен
    assert status == 200

    # у каждого свои волны и свои настройки
    sid = client.post("/api/wave/start", json={}, headers=owner).json()["session"]
    assert client.post(f"/api/wave/{sid}/shake", headers=guest).status_code == 404
    client.put("/api/settings", json={"character": "discover"}, headers=guest)
    assert client.get("/api/settings", headers=owner).json()["settings"]["character"] != "discover"
    assert client.get("/api/settings", headers=guest).json()["settings"]["character"] == "discover"
    assert built == [("Guest", "pw-guest")]  # пространство гостя заведено один раз, его же паролем

    # сервер настраивает только администратор; гостю не показываем учётку сервиса
    info = client.get("/api/server", headers=guest).json()
    assert info["can_manage"] is False and "navidrome_user" not in info["server"]
    assert client.put("/api/server", json={"navidrome_url": "x", "navidrome_user": "y"}, headers=guest).status_code == 403
    assert client.get("/api/server", headers=owner).json()["can_manage"] is True
