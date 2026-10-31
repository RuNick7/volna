from __future__ import annotations

import argparse
import logging

import uvicorn

from .app import create_app
from .config import Settings


def main() -> None:
    parser = argparse.ArgumentParser(prog="volna", description="Прототип «Моей волны» для Navidrome")
    parser.add_argument("--demo", action="store_true", help="синтетическая библиотека вместо Navidrome")
    parser.add_argument("--host", help="адрес (по умолчанию из VOLNA_HOST или 127.0.0.1)")
    parser.add_argument("--port", type=int, help="порт (по умолчанию из VOLNA_PORT или 8765)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    # httpx пишет каждый запрос вместе с адресом, а в адресах Subsonic — токен авторизации
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    settings = Settings.from_env(force_demo=args.demo)
    host = args.host or settings.host
    port = args.port or settings.port
    # без NAVIDROME_URL сервис берёт сервер, сохранённый в настройках, а если его нет — демо
    source = (
        "демо-библиотека" if settings.force_demo
        else f"Navidrome {settings.navidrome_url}" if settings.navidrome_url
        else "сервер из настроек или демо-библиотека"
    )
    if settings.auth:
        source += ", вход для любого пользователя Navidrome"
    print(f"Volna: http://{host}:{port}  ({source})")
    uvicorn.run(create_app(settings), host=host, port=port, log_level="warning")


if __name__ == "__main__":
    main()
