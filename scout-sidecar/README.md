# Pulse Scout Sidecar

MTProto-сервис (Telethon) для Jarvis: рассылки в ЛС, парсинг групп, Ad Radar observer.

Java-бот вызывает REST API — см. [../docs/SCOUT_SIDECAR_API.md](../docs/SCOUT_SIDECAR_API.md).

## Быстрый старт

```bash
cd scout-sidecar
python -m venv .venv
.venv\Scripts\activate          # Windows
pip install -r requirements.txt
copy .env.example .env
copy accounts.example.json accounts.json
# Заполните TELEGRAM_API_ID / TELEGRAM_API_HASH в .env
python scripts/login.py 1       # вариант A: SMS-код
# или, если есть auth key hex из tdata:
copy accounts.secrets.example.json accounts.secrets.json
# вставьте ПОЛНЫЙ auth_key_hex (512 символов) для id 1 и 2
python scripts/import_auth_key.py
python main.py                  # http://127.0.0.1:8090
```

## Готовые аккаунты (auth key + DC)

Если купили аккаунт с **Auth Key (HEX)** и **DC ID** (не tdata-папка):

1. `accounts.json` — id **1** = OUTREACH (рассылки), id **2** = OBSERVER (radar/парсинг).
2. `accounts.secrets.json` — полный hex-ключ **без `...`**, 512 символов на аккаунт.
3. `python scripts/import_auth_key.py` — создаст `sessions/*.session`.
4. Чешские аккаунты: включите **EU SOCKS5** в `.env` (`PROXY_*`), иначе Telegram может резать сессию.

Проверка: `GET http://127.0.0.1:8090/health` → `"ok": true`, 2 аккаунта.

## Связка с Java

1. На dev при пустой таблице `scout_accounts` бот сам создаёт `outreach-1` (id=1) и `observer-1` (id=2) — **id должны совпадать** с `accounts.json`.
2. В `application-local.yaml`:

```yaml
pulse:
  outreach:
    dispatch-enabled: true
  scout:
    enabled: true
    sidecar-url: http://127.0.0.1:8090
    admin-telegram-ids: [YOUR_TELEGRAM_ID]
```

## Эндпоинты

| Method | Path | Описание |
|--------|------|----------|
| GET | `/health` | Статус + список аккаунтов |
| POST | `/v1/dm/send` | ЛС по @username |
| POST | `/v1/group/members` | Парсинг участников группы |
| POST | `/v1/chat/scan` | Поиск ключевых слов в чате |

## Сессии

Файлы: `sessions/<session>.session` — **не коммитить**, добавлены в `.gitignore`.

## Прокси

Для серых аккаунтов — SOCKS5 в `.env`:

```
PROXY_TYPE=socks5
PROXY_HOST=127.0.0.1
PROXY_PORT=10808
```
