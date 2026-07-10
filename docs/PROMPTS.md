# ChannelPulse AI — LLM Prompts v1.0

> Промпты для генерации идей и постов. Все ответы — **strict JSON**.
> Связанные документы: [TECHNICAL_SPEC.md](./TECHNICAL_SPEC.md), [USER_FLOW.md](./USER_FLOW.md)

---

## 0. Общие правила

| Параметр | Ideas | Posts |
|----------|-------|-------|
| Model | gpt-4o / claude-3-5-sonnet | gpt-4o / claude-3-5-sonnet |
| Temperature | 0.7 | 0.8 |
| Max tokens | 2000 (free) / 3000 (paid) | 5000 |
| Response format | JSON object | JSON object |
| Language | Русский | Русский (как канал) |

**Retry prompt** (при невалидном JSON):

```
Твой предыдущий ответ не является валидным JSON. 
Верни ТОЛЬКО JSON-объект без markdown, без ``` и без пояснений.
```

---

## 1. System prompt (общий для всех вызовов)

```
Ты — эксперт по контент-стратегии Telegram-каналов. 
Ты анализируешь статистику канала и создаёшь персонализированные рекомендации.

Правила:
1. Пиши на русском языке.
2. Учитывай нишу канала, tone of voice и паттерны успешных постов.
3. Не используй клише: «уникальный контент», «инновационный подход», «на самом деле».
4. Идеи должны быть конкретными — с темой, angle и понятным value для аудитории.
5. Посты должны звучать как автор канала, не как AI.
6. Ответ — ТОЛЬКО валидный JSON по указанной схеме. Без markdown, без текста до/после JSON.
```

---

## 2. Prompt: генерация идей (FREE — 3 идеи)

### 2.1. User prompt template

```
Проанализируй Telegram-канал и предложи 3 идеи для постов на ближайшие 7–14 дней.

## Канал
- Название: {{channel_title}}
- Подписчиков: {{subscriber_count}}
- Ниша (если известна): {{niche_or_unknown}}

## Статистика за {{period_from}} — {{period_to}}
- Постов за период: {{post_count}}
- Средние просмотры: {{avg_views}} ({{views_delta}}% к прошлому периоду)
- Средний ER: {{avg_er}}%
- Лучшее время публикации: {{best_time}}

## Топ-3 поста по просмотрам
{{#each top_posts}}
{{@index}}. [{{views}} просм., ER {{er}}%] {{text_preview}}
{{/each}}

## Примеры tone of voice (фрагменты лучших постов)
{{#each style_samples}}
---
{{full_text_truncated_500}}
{{/each}}

## Задача
Сгенерируй ровно 3 идеи. Каждая идея — с чётким обоснованием, почему зайдёт именно этой аудитории.
Формат поста укажи: longread / короткий / опрос / кейс / список / история.

Верни JSON:
{
  "ideas": [
    {
      "title": "string — заголовок идеи, до 100 символов",
      "reason": "string — обоснование 2–3 предложения",
      "format": "string — формат поста",
      "suggested_day": "string — рекомендуемый день недели"
    }
  ]
}
```

### 2.2. JSON Schema (validation)

```json
{
  "type": "object",
  "required": ["ideas"],
  "properties": {
    "ideas": {
      "type": "array",
      "minItems": 3,
      "maxItems": 3,
      "items": {
        "type": "object",
        "required": ["title", "reason", "format", "suggested_day"],
        "properties": {
          "title": { "type": "string", "maxLength": 512 },
          "reason": { "type": "string", "maxLength": 1000 },
          "format": { "type": "string" },
          "suggested_day": { "type": "string" }
        }
      }
    }
  }
}
```

---

## 3. Prompt: генерация идей (PAID — 8–12 идей)

### 3.1. User prompt template

```
Проанализируй Telegram-канал и предложи от 8 до 12 идей для постов на ближайшие 7–14 дней.

## Канал
- Название: {{channel_title}}
- Подписчиков: {{subscriber_count}}
- Username: @{{channel_username}}

## Статистика за {{period_from}} — {{period_to}}
- Постов: {{post_count}}
- Средние просмотры: {{avg_views}} ({{views_delta}}%)
- Средний ER: {{avg_er}}%

## Топ-5 постов
{{#each top_posts}}
{{@index}}. [{{views}} просм., ER {{er}}%, {{media_type}}] {{text_preview}}
{{/each}}

## Худшие 3 поста
{{#each worst_posts}}
{{@index}}. [{{views}} просм., ER {{er}}%] {{text_preview}}
Причина провала (аналитика): {{failure_reason}}
{{/each}}

## Работающие темы
{{#each working_topics}}
- {{topic}}: ER {{avg_er}}%, {{post_count}} постов
{{/each}}

## Лучшее время публикации
{{#each best_slots}}
- {{day}} {{time}} — ER {{er}}%
{{/each}}

## Tone of voice (образцы успешных постов)
{{#each style_samples}}
---
{{full_text_truncated_800}}
{{/each}}

## Задача
1. Предложи 8–12 разнообразных идей — не повторяй темы топ-постов дословно, но учитывай что работает.
2. 2–3 идеи должны быть « смелые» — новый angle для канала.
3. Чередуй форматы: longread, короткий, опрос, кейс, список, история, провокация.
4. Обоснование каждой идеи — конкретное, со ссылкой на данные (ER, темы, время).

Верни JSON:
{
  "ideas": [
    {
      "title": "string",
      "reason": "string — 2–4 предложения с опорой на статистику",
      "format": "string",
      "suggested_day": "string",
      "priority": "high | medium | experimental"
    }
  ]
}
```

### 3.2. JSON Schema

```json
{
  "type": "object",
  "required": ["ideas"],
  "properties": {
    "ideas": {
      "type": "array",
      "minItems": 8,
      "maxItems": 12,
      "items": {
        "type": "object",
        "required": ["title", "reason", "format", "suggested_day", "priority"],
        "properties": {
          "title": { "type": "string", "maxLength": 512 },
          "reason": { "type": "string", "maxLength": 1500 },
          "format": { "type": "string" },
          "suggested_day": { "type": "string" },
          "priority": { "enum": ["high", "medium", "experimental"] }
        }
      }
    }
  }
}
```

---

## 4. Prompt: генерация постов (PAID — 5–7 постов)

### 4.1. User prompt template

```
Напиши готовые тексты постов для Telegram-канала в стиле автора.

## Канал
- Название: {{channel_title}}
- Подписчиков: {{subscriber_count}}

## Tone of voice — ОБРАЗЦЫ (копируй стиль, длину предложений, обращение, эмодзи)
{{#each style_samples}}
---
{{full_text}}
{{/each}}

## Идеи для постов (напиши текст для каждой)
{{#each ideas}}
{{sort_order}}. {{title}}
   Формат: {{format}}
   Обоснование: {{reason}}
{{/each}}

## Требования к каждому посту
1. Длина: 500–2000 символов (как в образцах канала).
2. Два варианта текста (A и B) — разный hook, одна тема.
3. Три варианта заголовка (короткие, кликабельные).
4. Один CTA — подписаться, обсудить, сохранить, перейти.
5. Без хештегов, если их нет в образцах.
6. Эмодзи — умеренно, как у автора.

Выбери {{posts_count}} лучших идей из списка (приоритет high и medium) и напиши посты.

Верни JSON:
{
  "posts": [
    {
      "idea_index": 1,
      "variant_a": "string — полный текст поста",
      "variant_b": "string — альтернативный текст",
      "headlines": ["string", "string", "string"],
      "cta": "string — призыв к действию"
    }
  ]
}
```

### 4.2. JSON Schema

```json
{
  "type": "object",
  "required": ["posts"],
  "properties": {
    "posts": {
      "type": "array",
      "minItems": 5,
      "maxItems": 7,
      "items": {
        "type": "object",
        "required": ["idea_index", "variant_a", "variant_b", "headlines", "cta"],
        "properties": {
          "idea_index": { "type": "integer", "minimum": 1 },
          "variant_a": { "type": "string", "minLength": 100, "maxLength": 4000 },
          "variant_b": { "type": "string", "minLength": 100, "maxLength": 4000 },
          "headlines": {
            "type": "array",
            "minItems": 3,
            "maxItems": 3,
            "items": { "type": "string", "maxLength": 150 }
          },
          "cta": { "type": "string", "maxLength": 512 }
        }
      }
    }
  }
}
```

---

## 5. Prompt: причины провала худших постов (PAID, optional)

Вызывается в `AnalyticsService` если rule-based reason недостаточен, или batch внутри PAID ideas prompt.

### 5.1. User prompt

```
Объясни коротко (1–2 предложения), почему каждый из постов показал низкий результат.

Контекст канала:
- Средний ER: {{avg_er}}%
- Средние просмотры: {{avg_views}}
- Работающие темы: {{working_topics}}

Посты:
{{#each worst_posts}}
{{@index}}. [{{views}} просм., ER {{er}}%]
{{full_text_truncated_600}}
{{/each}}

Верни JSON:
{
  "reasons": [
    {
      "message_index": 0,
      "reason": "string — 1–2 предложения"
    }
  ]
}
```

---

## 6. Prompt: определение ниши канала (optional, при подключении)

```
По названию канала и 10 последним постам определи нишу.

Канал: {{channel_title}}
Посты:
{{#each recent_posts}}
- {{text_preview}}
{{/each}}

Верни JSON:
{
  "niche": "string — одна из: expert, business, self_dev, finance, humor, lifestyle, smm, other",
  "niche_label_ru": "string — человекочитаемая ниша",
  "tone_keywords": ["string", "string", "string"]
}
```

---

## 7. Prompt builder (Java pseudocode)

```java
public String buildIdeasPrompt(AnalysisContext ctx, RequestType type) {
    var template = type == FREE ? FREE_IDEAS_TEMPLATE : PAID_IDEAS_TEMPLATE;
    return TemplateEngine.render(template, Map.of(
        "channel_title", ctx.channel().getTitle(),
        "subscriber_count", ctx.channel().getSubscriberCount(),
        "period_from", ctx.periodFrom(),
        "period_to", ctx.periodTo(),
        "post_count", ctx.snapshot().getPostCount(),
        "avg_views", ctx.snapshot().getAvgViews(),
        "views_delta", ctx.snapshot().getViewsDeltaPercent(),
        "avg_er", ctx.snapshot().getAvgEngagementRate(),
        "top_posts", ctx.topPosts(),
        "worst_posts", ctx.worstPosts(),
        "working_topics", ctx.snapshot().getWorkingTopics(),
        "best_slots", ctx.snapshot().getBestPublishSlots(),
        "style_samples", ctx.styleSamples(5)
    ));
}
```

---

## 8. Style samples selection

| Request | Samples | Max chars each |
|---------|---------|----------------|
| FREE ideas | Top 3 posts by score | 500 |
| PAID ideas | Top 5 posts | 800 |
| PAID posts | Top 5 + 2 median posts | full (≤2000) |

**Score:** `views * 0.5 + engagementRate * 10000 * 0.3 + forwards * 10 * 0.2`

---

## 9. Quality checks (post-processing)

Перед сохранением в БД:

| Check | Action |
|-------|--------|
| `variant_a.length > 4096` | Truncate + log warning |
| Duplicate idea titles | Dedupe by Levenshtein > 0.85 |
| AI markers («Конечно!», «Вот ваш») | Strip via regex |
| Empty CTA | Default: «Напишите в комментариях, что думаете» |
| `idea_index` not found | Skip post, log error |

---

## 10. Пример filled prompt (PAID ideas, сокращённый)

**Input context:**
- Канал: «Финансы простым языком», 12 400 подписчиков
- avg_views: 3200, avg_er: 4.2%
- Top post: «3 ошибки новичка на бирже» — 8900 views

**Expected JSON output (фрагмент):**

```json
{
  "ideas": [
    {
      "title": "Почему ваш «план инвестиций» не работает",
      "reason": "Посты с разбором ошибок дают ER 6.1% — в 1.5× выше среднего. Аудитория реагирует на конкретные anti-patterns, а не абстрактные советы.",
      "format": "longread",
      "suggested_day": "Вторник",
      "priority": "high"
    },
    {
      "title": "Опрос: сколько % дохода вы инвестируете?",
      "reason": "Опросы в канале набирают 120+ комментариев. Вовлекает пассивных подписчиков и даёт контент для следующего поста.",
      "format": "опрос",
      "suggested_day": "Четверг",
      "priority": "medium"
    }
  ]
}
```

---

## 11. Model fallback chain

```
1. gpt-4o (primary)
2. claude-3-5-sonnet (fallback on 429/5xx)
3. gpt-4o-mini (fallback on budget limit — урезать posts_count до 5)
```

---

*Документ: ChannelPulse AI Prompts v1.0 · Июль 2026*
