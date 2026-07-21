# ChannelPulse AI — Roadmap MVP (6 недель)

> Июль–Август 2026 · 1 разработчик full-time
> Документы: [PRODUCT_STATUS.md](./PRODUCT_STATUS.md) · [USER_FLOW.md](./USER_FLOW.md) · [TECHNICAL_SPEC.md](./TECHNICAL_SPEC.md) · [PROMPTS.md](./PROMPTS.md) · **[SCOUT_MODULE.md](./SCOUT_MODULE.md)** (авто-скаут, internal) · **[VISION_AND_MODULES.md](./VISION_AND_MODULES.md)** (курс после MVP: агент / Obsidian / админка)
>
> Актуальный статус продукта, changelog и план новых модулей (база ниш, скауты, рекламный маркетплейс) — в **[PRODUCT_STATUS.md](./PRODUCT_STATUS.md)**.
> Долгосрочный курс (сначала ядро продукта, потом модули) — в **[VISION_AND_MODULES.md](./VISION_AND_MODULES.md)**.

---

## Обзор

| Неделя | Фокус | Результат |
|--------|-------|-----------|
| 1 | Foundation | Бот отвечает, БД, сессии, онбординг |
| 2 | Channel + Stats | Подключение канала, сбор постов |
| 3 | Analytics + Free | Бесплатный анализ end-to-end |
| 4 | LLM + Paid | Платный запрос: идеи + посты |
| 5 | Payments + Publish | Stars/YooKassa, автопостинг |
| 6 | Polish + Launch | Retention, тесты, деплой |

---

## Неделя 1 — Foundation

**Цель:** скелет приложения, бот живой, пользователь проходит `/start`.

| День | Задачи | Done when |
|------|--------|-----------|
| Пн | Gradle deps, docker-compose (PG+Redis), Flyway V1–V5 | `./gradlew bootRun` стартует |
| Вт | JPA entities, repositories, UserService | User upsert по telegram_id |
| Ср | Bot + UpdateHandler + CallbackRouter | Callback routing работает |
| Чт | BotMessages, KeyboardFactory, MenuHandler | `/start`, главное меню |
| Пт | UserSession (Redis), BotState persistence | State переживает restart (Redis) |

**Deliverable:** бот приветствует, показывает меню, сохраняет user в PostgreSQL.

---

## Неделя 2 — Channel + Stats

**Цель:** пользователь подключает канал, бот собирает посты.

| День | Задачи | Done when |
|------|--------|-----------|
| Пн | ChannelHandler, connect flow (forward/@username) | Канал в БД |
| Вт | verifyBotAccess (getChatMember) | Ошибки «не админ» / «нет stats» |
| Ср | CollectStatsService — cache posts since connect | channel_posts заполняется |
| Чт | Forward fallback (user шлёт 20 постов) | Работает при <10 постов в кэше |
| Пт | ChannelHealthCheck scheduler | DISCONNECTED если бот удалён |

**Deliverable:** канал подключён, в БД ≥10 постов (или limited flag).

**Риск:** Telegram API не даёт history → mitigated через cache + forward.

---

## Неделя 3 — Analytics + Free Analysis

**Цель:** бесплатный hook работает end-to-end.

| День | Задачи | Done when |
|------|--------|-----------|
| Пн | AnalyticsService (pure Java metrics) | analysis_snapshots |
| Вт | Redis job queue + AnalysisWorker skeleton | Async enqueue/dequeue |
| Ср | IdeasGenerationService (FREE, 3 идеи) + LlmClient | LLM → content_ideas |
| Чт | Progress messages (editMessageText) | UX как в USER_FLOW §4.6–4.7 |
| Пт | Free analysis guard (1 раз), history save | free_analysis_used = true |

**Deliverable:** бесплатный анализ: топ-3, время, 3 идеи, CTA на покупку.

---

## Неделя 4 — Paid Request + Posts

**Цель:** платный запрос списывает баланс, генерирует полный результат.

| День | Задачи | Done when |
|------|--------|-----------|
| Пн | RequestHandler: confirm, charge, enqueue | balance -1, refund on fail |
| Вт | PAID ideas prompt (8–12) | PROMPTS.md §3 |
| Ср | PostsGenerationService (5–7 posts) | generated_posts |
| Чт | ResultHandler: analytics, ideas, posts navigation | Inline pagination |
| Пт | Worst posts reasons (LLM optional) | Полная аналитика |

**Deliverable:** платный запрос → полный результат по USER_FLOW §4.12–4.16.

---

## Неделя 5 — Payments + Autoposting

**Цель:** монетизация и публикация постов.

| День | Задачи | Done when |
|------|--------|-----------|
| Пн | PaymentHandler, package selection UI | 3 пакета из seed |
| Вт | Telegram Stars (sendInvoice, successful_payment) | +N requests на баланс |
| Ср | YooKassa create payment + webhook | Альтернативная оплата |
| Чт | PublishHandler: preview → edit → confirm | publish to channel |
| Пт | published_posts tracking, balance_transactions audit | Полный audit trail |

**Deliverable:** пользователь покупает пакет и публикует пост через бота.

---

## Неделя 6 — Polish + Launch

**Цель:** production-ready MVP.

| День | Задачи | Done when |
|------|--------|-----------|
| Пн | Retention schedulers (4 типа push) | USER_FLOW §5 |
| Вт | Edge cases: double request, channel disconnected, 4096 limit | §9 matrix |
| Ср | Promo codes (first_10, return_5) | PaymentService |
| Чт | Integration tests, actuator metrics | CI green |
| Пт | Deploy (VPS/Docker), monitoring, soft launch | 5 beta-каналов |

**Deliverable:** MVP в production, метрики в Grafana/логах.

---

## Post-MVP backlog (после запуска)

> **Приоритет сейчас:** допилить продукт + канал бренда. Скауты — фаза 3, см. [PRODUCT_STATUS.md](./PRODUCT_STATUS.md) §6.

| Приоритет | Фича | Оценка |
|-----------|------|--------|
| **P0** | Сохранение метрик в БД + inline-разбор | 1 нед |
| **P0** | Канал продукта + публикация постов из бота | 1 нед |
| P1 | Модуль A: бенчмарки ниши | 2 нед |
| P1 | Модуль E: News Intelligence | 4–5 нед |
| P2 | Память стиля канала (tone profile) | 1 нед |
| P2 | Отложенная публикация | 1 нед |
| P3 | TDLib sidecar (full history) | 2 нед |
| **Фаза 3** | Модуль D: скауты + CRM (internal) | 9–10 нед |
| P3 | Модуль C: рекламный маркетплейс | 4 нед |

---

## KPI для go/no-go (конец недели 6)

| Метрика | Target |
|---------|--------|
| Free → Paid conversion | ≥8% |
| Analysis success rate | ≥95% |
| LLM parse error rate | <5% |
| Payment success rate | ≥90% |
| Publish success rate | ≥98% |
| Avg analysis time | <4 min |

---

## Стек деплоя (рекомендация)

```
VPS (2 vCPU, 4GB RAM)
├── docker-compose
│   ├── app (Spring Boot)
│   ├── postgres:16
│   └── redis:7
├── nginx (webhook SSL)
└── backups (pg_dump daily)
```

---

*Roadmap v1.0 · Июль 2026*
