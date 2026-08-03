package org.example.pulse_ai.domain.product;

public enum ProductPostRubric {
    MORNING("☀️ Утренний бриф", "Async-дайджест: что проверить в канале сегодня"),
    PROMO("🎁 Акция / халява", "Бесплатный разбор, бонусы, ограниченное предложение"),
    FEATURE("⚡ Фича бота", "Одна возможность Pulse AI — на примере"),
    DEMO("📊 Демо разбора", "Показать ценность анализа без выдуманных цифр"),
    INSIGHT("🧠 Инсайт", "Один вывод про контент в Telegram"),
    NEWS_DAY("📰 День в TG", "Тренд или новость — только из проверенных источников"),
    HOWTO("💡 Как пользоваться", "3 шага: ссылка → отчёт → идеи"),
    CHANGELOG("📋 Changelog", "Что нового в боте"),
    COMMUNITY("👥 Чат / подписка", "Зачем ассистент и подписка (мягко)"),
    CASE("📈 Мини-кейс", "Было/стало — обобщённо"),
    FEATURE_VOTE("🗳 Голос за фичу", "Аудитория выбирает, что добавить в ассистента");

    private final String label;
    private final String hint;

    ProductPostRubric(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }
}
