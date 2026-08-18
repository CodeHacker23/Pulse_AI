# Pulse AI — HANDOFF (передача контекста)

> **Читать первым** при открытии проекта с другого аккаунта Cursor / новым агентом.  
> Обновлено: **13 августа 2026**  
> Язык общения с владельцем: **русский**, по делу, без воды.

Связанные доки:  
[PROJECT_OVERVIEW](./PROJECT_OVERVIEW.md) · [SCOUT_OPS](./SCOUT_OPS.md) · [PRODUCT_STATUS](./PRODUCT_STATUS.md) · [VISION_AND_MODULES](./VISION_AND_MODULES.md) · [SCOUT_SIDECAR_API](./SCOUT_SIDECAR_API.md)

Промпт для нового чата:

```
Прочитай docs/HANDOFF.md, docs/PROJECT_OVERVIEW.md, docs/SCOUT_OPS.md и AGENTS.md.
Продолжаем Pulse AI с места остановки. Не выдумывай — сверяйся с этими файлами.
```

---

## 0. Одна фраза

**Pulse AI** = Telegram-бот для ведения канала (контент → ритм → ассистент по лидам)  
+ **внутренняя кухня скаутов** (user-аккаунты через Telethon sidecar) для роста самого продукта.

Бот: `@Pulsse_AI_bot`  
Админка локально: `http://127.0.0.1:8081/admin/?token=…` (токен в `application-local.yaml`, файл **не в git**)

---

## 1. Стек и порты

| Компонент | Стек | Порт | Запуск |
|-----------|------|------|--------|
| Бот + админка | Java 17, Spring Boot, Gradle | **8081** (local) | `.\gradlew.bat bootRun --args=--spring.profiles.active=local` |
| Scout sidecar | Python, FastAPI, Telethon | **8090** | `scout-sidecar\.venv\Scripts\python.exe main.py` из `scout-sidecar/` |
| БД local | H2 file `./data/channelpulse` | — | Flyway в local **выключен** → схема патчится `H2LocalSchemaPatcher` + ручные SQL при нужде |
| БД prod-like | Postgres + Flyway `V1…V29` | — | миграции в `src/main/resources/db/migration/` |

**Секреты (не коммитить):**  
`application-local.yaml`, `scout-sidecar/.env`, `accounts.json`, `accounts.secrets.json`, `sessions/*.session`, `data/`.

Примеры: `application-local.yaml.example`, `scout-sidecar/.env.example`, `accounts.example.json`.

---

## 2. Где остановились (13 августа 2026)

### Сделано в последних сессиях

**Скауты / админка (ранее)**
1. **Кабинет TG в админке** — UI как Telegram: слева диалоги, справа переписка, ответ, прочитано.  
2. **Карточка идентичности (lolz-style)** — телефон, User ID, DC, auth key hint, shop note; живёт без сессии.  
3. **ID-диапазоны** — watch `1–99` (+overflow 1000–1999), send `100–999` (+2000–9999).  
4. **Честный статус** — `ACTIVE` ≠ «в сети»; без TG нельзя Resume.  
5. **Парсинг без admin** — fallback по сообщениям; русские ошибки.  
6. **Сгорание сессий** — `AuthKeyDuplicated`: humanize, audit дублей ключей, блок заливки того же ключа в два слота, wipe/delete/burn UI.  
7. **SpamBot** — только SENDER/OUTREACH, **max 4×/сутки** (`spambot_today`), сброс 00:05 MSK.  
8. **Общий пул групп** — `scout_target_chats` + `scout_chat_memberships`; join **1 шт / ~30с**; не скопом.  
9. **Failover / handoff** — сгорел PARSER/OBSERVER → очередь на живого; кнопки «В мёртвые», «Перенести пул», «В пул групп».  
10. **Защита от пустых .session** при status-check (закрытие SQLite + удаление фантомов).
11. **Архив ЛС скаутов** — текст + edit/delete + **медиа** (`data/scout_media/`, V30/V31). Sidecar качает сразу (TTL). Кабинет fallback «из архива». Не релей-бот.
12. **Бэкфилл архива** — при первом коннекте sidecar тянет до 40 ЛС × 80 сообщений (+ медиа). Повтор не чаще чем раз в 7 дней; вручную: кабинет «Бэкфилл архива» / карточка «Архив ЛС».
13. **Join ops** — ≤60 вступлений/акк в сутки (MSK); FLOOD → карантин очереди 2ч. Матрица группы×скауты на вкладке ЦА.
14. **Профиль ЦА** — не хардкод ниши. Слои: токены из постов → LLM только по этим токенам → грунт запросов (выброс «бизнес»/галлюцинаций) → мультипоиск + отсев площадок по пересечению. Пишется в `channels.audience_brief` (V33).

### Продуктовое решение (архив — текст+медиа в коде)

**Архив ЛС своих скаутов** (ops-бэкап), **не** релей-бот.

- Пока сессия жива — sidecar копирует текст + edit/delete + медиа (сразу на диск) и **бэкфиллит историю** при коннекте.
- Сгорел акк — в TG пусто, в Pulse архив остаётся (бейдж «из архива»).
- Не обещать «вернуть ЛС в Telegram».

### Инвентарь скаутов на момент сессии ~29.07.2026 (мог устареть — перепроверь dashboard)

| ID | Роль | Номер / заметка | Статус тогда |
|----|------|-----------------|--------------|
| #1 | PARSER | +48797169701 @Kamill_Ko | живой, TG ok |
| #2 | OBSERVER | 420723571671 (карточка покупки) | без своей сессии / WARMING после wipe дубля |
| #33 | SENDER | 420723571671 | **BURNED** (ключ убит) |

**Выученный факт:** `#1` и `#2` одно время держали **один auth_key** → риск сжечь обоих. Дубль снят.  
Чешский `420723571671` был и в #2 (shop note) и в #33 — один магазинный акк в двух слотах = плохо.

Закупки в очереди обсуждались: КЗ / UK / US / UZ — **разложить роли + 1 прокси на акк до первого коннекта**, новые UZ греть.

---

## 3. Архитектура скаутов (обязательно понять)

```
Админка / Java-бот
      │ HTTP
      ▼
scout-sidecar :8090  (Telethon)
      │ 1 session = 1 proxy = 1 IP
      ▼
Telegram MTProto
```

| Тип | ЛС | Join/parse | SpamBot |
|-----|----|------------|---------|
| PARSER / OBSERVER | нет | да | **нет** |
| SENDER / OUTREACH | да, лимит ~30–40/день | нет (не их работа) | да, ≤4/день |

**Знание vs доступ**

| Знание (переживает burn) | Доступ (умирает с ключом) |
|--------------------------|---------------------------|
| пул групп `scout_target_chats` | `.session` / tdata |
| memberships / handoff | «уже внутри чата в TG» |
| identity card, prospects, logs, архив ЛС | живые ЛС в Telegram (без копии — пусто) |

Подробно: [SCOUT_OPS.md](./SCOUT_OPS.md).

### Ключевые классы / файлы

| Путь | Зачем |
|------|--------|
| `web/PulseAdminApiController.java` | REST админки |
| `domain/scout/ScoutAccountService.java` | статусы, SpamBot, burn, ID bands |
| `domain/scout/ScoutChatPoolService.java` | пул, enroll, handoff |
| `domain/scout/ScoutChatJoinScheduler.java` | join раз в ~30с |
| `domain/scout/SidecarAdminClient.java` | HTTP к sidecar |
| `domain/scout/ScoutMessageArchiveService.java` | архив ЛС |
| `config/H2LocalSchemaPatcher.java` | схема H2 без Flyway |
| `static/admin/*` | UI админки |
| `db/migration/V30__scout_message_archive.sql` | архив ЛС |
| `db/migration/V29__scout_chat_pool.sql` | пул + spambot counters |
| `scout-sidecar/main.py` | FastAPI |
| `scout-sidecar/archive.py` | push new/edit/delete в Java |
| `scout-sidecar/clients.py` | Telethon ops, humanize errors |
| `scout-sidecar/account_registry.py` | sessions, identity, audit, wipe |
| `scout-sidecar/scripts/audit_sessions_cli.py` | дубли ключей без сети |

### Бан TG — шпаргалка

| Ошибка | Что мертво | Лечится сменой API_ID? |
|--------|------------|-------------------------|
| `AUTH_KEY_DUPLICATED` | сессия | **нет** |
| `PEER_FLOOD` / `FLOOD_WAIT` | флаг/таймер | нет |
| `USER_DEACTIVATED` / phone ban | номер | нет |

---

## 4. Продукт для клиентов (ядро бота)

Три столпа: **Контент → Ритм → Ассистент (подписка)**.  
Метрики — вспомогательные, не второй дашборд TG.

**Биллинг (ориентиры):**

Пакеты разборов: START 10/890₽ · CONTENT 18/1490₽ · PRO 30/1990₽  

Ассистент: 3990 / 6990 / 9990 ₽ (квоты ЛС 100 / 500 / 1000)

Подробности и статус фич: [PROJECT_OVERVIEW](./PROJECT_OVERVIEW.md), [PRODUCT_STATUS](./PRODUCT_STATUS.md).

Также в коде/доках: Ad Radar / сделки, product channel (changelog бренда), story arcs, content plan, TGStat.

---

## 5. Метрики и «что считать успехом» (обсуждённый контекст)

| Контур | Метрики / сигналы |
|--------|-------------------|
| Контент | ER, просмотры, топ/худшие посты, слоты «🔥», идеи под бриф |
| Ассистент | лиды из комментов, won/lost, follow-up 24ч, квота ЛС |
| Скауты watch | чатов в пуле, PENDING/JOINED/FAILED, живые TG, без дублей ключей |
| Скауты send | sentToday / dailyLimit, SpamBot N/4, FLOOD/PEER_FLOOD |
| Реклама | placements, deals, quality score (ещё не полный ROI/эскроу) |
| Операторка | sidecar reachable, proxy alive, audit duplicates=[] |

Админка показывает: `Всего N · рабочих · сгоревших`, пул `ACTIVE/PENDING/JOINED`, SpamBot `N/4`.

---

## 6. Жёсткие правила (не нарушать)

1. **1 купленный акк = 1 слот = 1 `.session` = 1 sticky-прокси.**  
2. После tdata — **не** открывать Desktop на том же номере.  
3. Не лить один tdata/auth_key в два скаута (sidecar теперь **откажет**).  
4. PARSER не пишет ЛС и не жмёт SpamBot.  
5. На `PEER_FLOOD` — пауза 24–48ч, не крутить IP сразу.  
6. Local Flyway off — новые таблицы добавлять и в `Vxx` **и** в `H2LocalSchemaPatcher`.  
7. Секреты и `.session` в git не класть.  
8. Не обещать «восстановить ЛС после burn» без заранее включённого архива.

---

## 7. Следующие шаги (бэклог после handoff)

### P0 — архив ЛС скаутов (текст + медиа)

Сделано: live ingest, медиа, **бэкфилл при коннекте** + кнопка в админке.

1. Не обещать «восстановить ЛС в Telegram» — архив только у нас.

### P1 — скауты ops

Сделано в этой сессии: суточный лимит join ≤60; матрица группы×скауты; карантин очереди 2ч после FLOOD; **статус FLOOD_WAIT + quarantine_until** (Старт заблокирован, авто-ACTIVE по таймеру); при создании карточки — автопрокси + enroll парсера.

- ≥2 живых PARSER/OBSERVER на один пул (в UI предупреждение, если меньше).
- Разложить новые покупки вручную: роль → прокси до коннекта → tdata (чеклист в админке).
- Карантин статуса после FLOOD — **готово**.

### P2 — продукт

- Outbound UX кампаний в боте — **дожато в этой сессии**: парсинг → черновик кампании → квота ЛС → запуск → ответы.  
- Ad deal / matchmaker дожать.  
- См. PRODUCT_STATUS / VISION.

---

## 8. Чеклист «поднять стенд»

```powershell
# sidecar
cd scout-sidecar
.\.venv\Scripts\python.exe main.py

# бот (другой терминал)
cd ..
.\gradlew.bat bootRun --args=--spring.profiles.active=local
```

Проверки:

1. `http://127.0.0.1:8090/health` (или status)  
2. `http://127.0.0.1:8081/admin/?token=…`  
3. Dashboard: аккаунты + `sessions/audit` без duplicates  
4. ЦА: статус пула чатов  

Дубли ключей без сети:

```powershell
cd scout-sidecar
.\.venv\Scripts\python.exe scripts\audit_sessions_cli.py
```

---

## 9. История обсуждений (сжатый лог сессий)

| Тема | Итог |
|------|------|
| UI кабинета как у Wappi/Telegram | Сделано в admin |
| Lolz-карточка акка | phone/userId/dc/hint/note |
| Все акки «сгорели» | Часть — дубли ключей; #33 burned; #1 жил |
| Удалить/вкладки мёртвых | фильтр Рабочие/Сгоревшие, burn/delete/wipe |
| SpamBot часто | лимит 4/день, только senders |
| Списки групп руками каждый раз | общий пул + enroll + handoff |
| Join 200 сразу | очередь ~30с |
| Галочки как у кореша | = memberships; UI матрицы ещё нет |
| Отказоустойчивость | знание в Pulse; failover пула; ЛС только через будущий архив |
| «Бот сохраняет сгорающие фото» | разобрали: копия при получении; для нас — архив своих скаутов |
| Экспорт контекста на другой Cursor | этот HANDOFF + AGENTS.md + rules |

Транскрипты Cursor на машине (не в git):  
`%USERPROFILE%\.cursor\projects\c-Users-ilari-OneDrive-Desktop-Pulse-AI\agent-transcripts\`  
Главный длинный тред скаутов: `99e27a43-808f-4b8f-b2b9-e46b01c58e9a`.

---

## 10. Контакты артефактов

| Артефакт | Где |
|----------|-----|
| Обзор продукта | `docs/PROJECT_OVERVIEW.md` |
| Ops скаутов | `docs/SCOUT_OPS.md` |
| API sidecar | `docs/SCOUT_SIDECAR_API.md` |
| Статус/roadmap | `docs/PRODUCT_STATUS.md`, `docs/ROADMAP.md` |
| Этот handoff | `docs/HANDOFF.md` |
| Правила агента | `AGENTS.md`, `.cursor/rules/` |

**Конец handoff.** Обновляй §2 и §7 при каждой существенной сессии.
