"""Вход в сервис аккаунтом Navidrome.

Нужен, когда сервис открыт в интернет: без входа любой мог бы слушать библиотеку и
менять настройки. Пускаем только владельца, то есть аккаунт, под которым сервис сам
ходит в Navidrome. Пароль проверяет Navidrome, у нас хранятся лишь хэши выданных токенов.
Клиент присылает токен заголовком `Authorization: Bearer …`, веб-версия — cookie.
"""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
from collections import defaultdict, deque
from typing import Callable

from fastapi import Request

from .store import Store

TOKENS_KEY = "auth_tokens"
COOKIE = "volna_token"
TOKEN_TTL = 365 * 86400
MAX_FAILURES = 5  # столько неудачных попыток с одного адреса…
FAILURE_WINDOW = 600  # …за 10 минут, дальше ждём


class LoginError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def _digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def client_ip(request: Request) -> str:
    # Caddy подставляет настоящий адрес в X-Forwarded-For, чужой заголовок он не пропускает
    forwarded = request.headers.get("x-forwarded-for", "")
    return forwarded.split(",")[0].strip() or (request.client.host if request.client else "")


def request_token(request: Request) -> str:
    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        return header[7:].strip()
    return request.cookies.get(COOKIE, "")


class Auth:
    def __init__(self, store: Store, enabled: bool, verify: Callable[[str, str], bool] | None = None):
        self.store = store
        self.enabled = enabled
        self.verify = verify or (lambda user, password: False)  # задаёт приложение: проверка в Navidrome
        self._lock = threading.Lock()
        self._tokens: dict[str, dict] = store.setting(TOKENS_KEY, {}) or {}
        self._failures: dict[str, deque[float]] = defaultdict(deque)

    def user(self, token: str) -> str | None:
        """Чей это токен; None — токена нет или он истёк."""
        if not token:
            return None
        entry = self._tokens.get(_digest(token))
        if entry is None or entry["created"] + TOKEN_TTL < time.time():
            return None
        return entry["user"]

    def login(self, user: str, password: str, *, ip: str = "", device: str = "") -> str:
        now = time.time()
        with self._lock:
            failures = self._failures[ip]
            while failures and failures[0] < now - FAILURE_WINDOW:
                failures.popleft()
            if len(failures) >= MAX_FAILURES:
                raise LoginError(429, "Слишком много попыток входа, подождите несколько минут")
        if not user or not password or not self.verify(user, password):
            with self._lock:
                self._failures[ip].append(now)
            raise LoginError(401, "Неверный логин или пароль")
        token = secrets.token_urlsafe(32)
        with self._lock:
            self._failures.pop(ip, None)
            self._tokens = {
                digest: entry for digest, entry in self._tokens.items() if entry["created"] + TOKEN_TTL >= now
            }
            self._tokens[_digest(token)] = {"user": user, "created": now, "device": device[:40]}
            self.store.put_setting(TOKENS_KEY, self._tokens)
        return token

    def logout(self, token: str) -> None:
        with self._lock:
            if self._tokens.pop(_digest(token), None) is not None:
                self.store.put_setting(TOKENS_KEY, self._tokens)
