# Volna на сервере

Сервис Volna работает на том же сервере, что Navidrome и AudioMuse, отдельным Docker-контейнером. Приложение и веб-версия открываются по адресу Navidrome с путём `/volna`, например `https://music.example.com/volna/`. Войти может любой пользователь Navidrome своим логином и паролем. У каждого свои волна, история пропусков, профиль вкуса и настройки, а лайки и прослушивания уходят в Navidrome от его имени. Общий у всех только кэш звучания из AudioMuse. Адреса Navidrome и AudioMuse меняет администратор Navidrome или тот, кто подключил сервис.

```
телефон ──https──▶ Caddy ─┬─ /volna/* ──▶ volna:8765 ──▶ navidrome:4533
                          │                          └─▶ audiomuse-flask:8000
                          └─ остальное ─▶ navidrome:4533
```

## Что где лежит

| Путь на сервере | Что это |
|---|---|
| `/opt/volna/docker-compose.yml` | Контейнер Volna, копия [`docker-compose.yml`](docker-compose.yml) |
| `/opt/volna/wave-service/` | Код сервиса, из него собирается образ |
| `/opt/volna/data/` | Настройки, сохранённый сервер, пароли вошедших пользователей, история волны каждого (`server-*.db`). Только эта папка и важна |

Контейнер подключён к сети `navidrome-stack_default`, поэтому ходит к Navidrome и AudioMuse напрямую, а Caddy ходит к нему по имени `volna`. Наружу контейнер портов не открывает.

## Выкатить новую версию

```bash
./deploy/deploy.sh
```

Скрипт копирует `wave-service/` на сервер по SSH, пересобирает образ и перезапускает контейнер. Данные в `/opt/volna/data` не трогаются. Куда выкатывать, скрипт берёт из `VOLNA_SERVER=root@host` или из файла `deploy/.server` с той же строкой: файл не попадает в git, адрес сервера остаётся только у вас.

## Caddy

В `Caddyfile` стека Navidrome основной сайт отдаёт `/volna/*` в Volna, а остальное, как и раньше, в Navidrome:

```caddy
{$ND_DOMAIN}:{$HTTPS_PORT} {
	encode zstd gzip

	redir /volna /volna/ 308
	handle_path /volna/* {
		reverse_proxy volna:8765 {
			header_up X-Forwarded-Prefix /volna
		}
	}

	handle {
		reverse_proxy navidrome:4533
	}
}
```

После правки Caddy перечитывает конфиг без перезапуска:

```bash
docker exec navidrome-stack-caddy-1 caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

## Полезное

```bash
cd /opt/volna && docker compose logs -f volna   # журнал
cd /opt/volna && docker compose restart volna   # перезапуск
```
