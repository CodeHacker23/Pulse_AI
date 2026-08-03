# Scout Sidecar API (MTProto)

Java-бот вызывает sidecar (Python Telethon) по REST.

**Код sidecar:** [`scout-sidecar/README.md`](../scout-sidecar/README.md)

Base URL: `pulse.scout.sidecar-url`, напр. `http://127.0.0.1:8090`

## POST /v1/dm/send

```json
{ "accountId": 1, "username": "durov", "text": "Привет!" }
```

Ответ: `{ "ok": true, "messageId": 123 }` или `{ "ok": false, "error": "..." }`

## POST /v1/group/members

```json
{ "accountId": 1, "link": "https://t.me/+xxx", "limit": 500 }
```

Ответ: `{ "ok": true, "usernames": ["user1", "user2"] }`

## POST /v1/chat/scan

```json
{ "accountId": 2, "link": "@marketing_chat", "keywords": ["реклам", "прайс"] }
```

Ответ: `{ "ok": true, "hits": [{ "snippet": "...", "keyword": "реклам" }] }`

## БД `scout_accounts`

| label | account_type | status | daily_limit |
|-------|--------------|--------|-------------|
| outreach-1 | OUTREACH | ACTIVE | 15 |
| observer-1 | OBSERVER | ACTIVE | 0 |

`accountId` в API = `scout_accounts.id`.

## Включение отправки

```yaml
pulse:
  outreach:
    dispatch-enabled: true
  scout:
    enabled: true
    sidecar-url: http://127.0.0.1:8090
    admin-telegram-ids: [YOUR_TELEGRAM_ID]
```
