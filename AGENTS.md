# AGENTS.md — правила для AI в репозитории Pulse AI

Ты работаешь в репозитории **Pulse AI**. Перед крупными изменениями прочитай:

1. `docs/HANDOFF.md` — где остановились, инвентарь, бэклог  
2. `docs/PROJECT_OVERVIEW.md` — продукт целиком  
3. `docs/SCOUT_OPS.md` — если трогаешь скаутов / sidecar / админку  

Общайся с владельцем **по-русски**, коротко и по делу.

---

## Стек

- **Java 17 / Spring Boot / Gradle** — бот, биллинг, CRM, админ API  
- **Python FastAPI + Telethon** — `scout-sidecar/` (MTProto user-аккаунты)  
- **Админка** — `src/main/resources/static/admin/`  
- Local: H2 + `H2LocalSchemaPatcher` (Flyway в `application-local` **выключен**)  
- Prod-like: Postgres + Flyway `V*.sql`

Порты local: бот **8081**, sidecar **8090**.

---

## Нельзя

- Коммитить секреты: `application-local.yaml`, `.env`, `accounts.json`, `accounts.secrets.json`, `sessions/`, `data/`  
- Лить один `tdata` / auth_key в два слота скаута  
- SpamBot / исходящие ЛС с PARSER/OBSERVER  
- SpamBot чаще 4 раз в сутки на SENDER  
- Массовый join скопом (только очередь пула ~30с)  
- Обещать восстановление ЛС после `AUTH_KEY_DUPLICATED` без заранее пишущего архива  
- Менять API_ID «чтобы оживить» сгоревший ключ — бесполезно  

---

## Скауты — модель

| Роль | ID band | Делает |
|------|---------|--------|
| PARSER / OBSERVER | 1–99 (+1000–1999) | join, parse, radar |
| SENDER / OUTREACH | 100–999 (+2000–9999) | ЛС, лимит/день |

Пул групп: `scout_target_chats` + `scout_chat_memberships`.  
Failover: `ScoutChatPoolService.handoff`.  
Identity: sidecar `accounts.json` / secrets + админ-карточка.

При новых таблицах: миграция Flyway **и** патч в `H2LocalSchemaPatcher`.

---

## Следующая согласованная фича

**Архив ЛС своих скаутов** (ops-бэкап): текст + edit/delete → потом медиа.  
Не релей-бот. Детали — `docs/HANDOFF.md` §7.

---

## Стиль кода

- Минимальный diff, без лишних рефакторов и markdown «ради markdown»  
- Совпадай со стилем соседних файлов  
- Коммиты — только по просьбе пользователя; секреты не в git  
