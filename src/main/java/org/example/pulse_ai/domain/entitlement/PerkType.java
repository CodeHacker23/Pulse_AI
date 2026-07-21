package org.example.pulse_ai.domain.entitlement;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Каталог бонусов к пакетам. usesRemaining=null → безлимит на срок; daysValid=null → без срока.
 */
public enum PerkType {

    POST_AUDIT(
            "🔬 Разбор поста",
            "Перешлите пост — узнаете, почему он зашёл или слил охват",
            null,
            null,
            List.of("START", "CONTENT", "PRO")
    ),
    HARD_AUDIT(
            "🔥 Жёсткий аудит",
            "Честный разбор без комплиментов: 3 дыры + 3 действия",
            1,
            null,
            List.of("START", "CONTENT", "PRO")
    ),
    COMMENTS(
            "💬 Голос аудитории",
            "Что спрашивают, что бесит, какие возражения — из комментариев",
            null,
            30,
            List.of("CONTENT", "PRO")
    ),
    DIGEST(
            "📬 Еженедельный дайджест",
            "Раз в неделю: что выросло, что просело, одна идея на 7 дней",
            null,
            30,
            List.of("CONTENT", "PRO")
    ),
    COMPETITOR(
            "⚔️ Анализ конкурента",
            "Сравнение с 1 похожим каналом — где вы сильнее и слабее",
            1,
            null,
            List.of("CONTENT", "PRO")
    ),
    ANTISPAM(
            "🛡 Защита комментариев",
            "Умная модерация + репутация в чате канала",
            null,
            30,
            List.of("PRO")
    ),
    SELLING(
            "💰 Продающие посты",
            "Генерация с фокусом на конверсию и оффер",
            null,
            30,
            List.of("PRO")
    ),
    LIBRARY(
            "📚 Библиотека хитов",
            "Подборка сильных постов вашей ниши",
            null,
            30,
            List.of("PRO")
    ),
    MANAGER(
            "🧑\u200d💼 Менеджер-агент в комментариях",
            "Мини-агент админа: ловит горячих лидов в комментариях и сразу пингует вас",
            null,
            30,
            List.of("CONTENT", "PRO")
    );

    private final String label;
    private final String hook;
    private final Integer defaultUses;
    private final Integer defaultDays;
    private final List<String> packageTiers;

    PerkType(String label, String hook, Integer defaultUses, Integer defaultDays, List<String> packageTiers) {
        this.label = label;
        this.hook = hook;
        this.defaultUses = defaultUses;
        this.defaultDays = defaultDays;
        this.packageTiers = packageTiers;
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public String hook() {
        return hook;
    }

    public Integer defaultUses() {
        return defaultUses;
    }

    public Integer defaultDays() {
        return defaultDays;
    }

    public List<String> packageTiers() {
        return packageTiers;
    }

    public static Optional<PerkType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(p -> p.name().equalsIgnoreCase(code))
                .findFirst();
    }

    public static List<PerkType> pickableForPackage(String packageCode) {
        return Arrays.stream(values())
                .filter(p -> p.packageTiers().contains(packageCode))
                .toList();
    }
}
