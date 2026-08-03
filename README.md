# Pulse AI

Telegram-бот для ведения каналов + внутренняя кухня скаутов (Telethon sidecar).

## С другого аккаунта Cursor — начни здесь

1. Открой папку репозитория  
2. Новый чат → промпт:

```
Прочитай docs/HANDOFF.md, docs/PROJECT_OVERVIEW.md, docs/SCOUT_OPS.md и AGENTS.md.
Продолжаем Pulse AI с места остановки.
```

| Файл | Зачем |
|------|--------|
| [docs/HANDOFF.md](docs/HANDOFF.md) | Где остановились, инвентарь, бэклог, правила |
| [AGENTS.md](AGENTS.md) | Краткие правила для агента |
| [docs/PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md) | Продукт целиком |
| [docs/SCOUT_OPS.md](docs/SCOUT_OPS.md) | Операционка скаутов |
| `.cursor/rules/pulse-ai.mdc` | Автоправила Cursor |

## Локальный запуск

- Бот: `.\gradlew.bat bootRun --args=--spring.profiles.active=local` → `:8081`  
- Sidecar: в `scout-sidecar/` → `.\.venv\Scripts\python.exe main.py` → `:8090`  
- Секреты: скопируй `application-local.yaml.example` → `application-local.yaml` (не в git)

Подробный чеклист — в HANDOFF §8.
