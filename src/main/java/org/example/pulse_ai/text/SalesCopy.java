package org.example.pulse_ai.text;

import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.persistence.entity.PackageEntity;

import java.util.List;

/**
 * Продающие тексты: якорение, дефицит, незавершённость, выгода vs боль.
 */
public final class SalesCopy {

    private SalesCopy() {
    }

    public static String packagesIntro() {
        return """
                💳 <b>Выберите свой темп роста</b>

                Один запрос = полный разбор канала + идеи + черновики + публикация.

                <i>Чем больше пакет — тем ниже цена запроса и больше бонусов на выбор.</i>

                👇 Средний вариант чаще всего окупается с <b>первого сильного поста</b>.""";
    }

    public static String packageLine(PackageEntity pack, boolean highlight) {
        int perRequest = pack.getRequestCount() > 0
                ? pack.getPriceRub() / pack.getRequestCount()
                : pack.getPriceRub();
        String anchor = highlight ? "⭐ " : "• ";
        String perks = pack.getPerkChoicesCount() > 0
                ? " + <b>" + pack.getPerkChoicesCount() + "</b> бонус" + perkWord(pack.getPerkChoicesCount()) + " на выбор"
                : "";
        String priority = pack.isIncludesPriority() ? " + ⚡ приоритет" : "";
        return anchor + TgHtml.b(pack.getName())
                + " — <b>" + pack.getRequestCount() + "</b> запросов"
                + perks + priority + "\n"
                + "   " + pack.getStarsAmount() + " ⭐ · ~" + perRequest + " ₽/запрос";
    }

    public static String invoiceDescription(PackageEntity pack) {
        return pack.getRequestCount() + " запросов + " + pack.getPerkChoicesCount()
                + " бонус" + perkWord(pack.getPerkChoicesCount()) + " на выбор · Pulse AI";
    }

    public static String paymentSuccessChoosePerks(String packName, int credited, int balance, int perksToPick) {
        return """
                ✅ <b>Готово — вы внутри.</b>

                Пакет «%s»: <b>+%d</b> запросов на балансе (<b>%d</b> всего).

                🎁 <b>Остался последний шаг</b> — выберите %d бонус%s.
                <i>Мозг откладывает выбор — а бонусы не активируются сами. Заберите сейчас, пока контекст свежий.</i>"""
                .formatted(
                        TgHtml.esc(packName),
                        credited,
                        balance,
                        perksToPick,
                        perksToPick == 1 ? "" : "а");
    }

    public static String paymentSuccessNoPerks(String packName, int credited, int balance) {
        return """
                ✅ <b>Оплата прошла — можно действовать.</b>

                «%s»: <b>+%d</b> запросов.
                Баланс: <b>%d</b>.

                Пришлите ссылку на канал — запустите разбор, пока мотивация на пике 👇"""
                .formatted(TgHtml.esc(packName), credited, balance);
    }

    public static String perkPickerIntro(String packName, int remaining, int total) {
        if (remaining == total) {
            return """
                    🎁 <b>Ваши бонусы — «%s»</b>

                    Выберите <b>%d</b> из списка. Каждый бонус — отдельная ценность, которую другие покупают отдельно.

                    <i>Совет: начните с «Разбор поста» — результат за 30 секунд.</i>"""
                    .formatted(TgHtml.esc(packName), total);
        }
        return """
                🎁 <b>Ещё %d бонус%s</b>

                Уже выбрано: <b>%d</b> из <b>%d</b>.
                <i>Доберите сейчас — незавершённое выбирают реже.</i>"""
                .formatted(
                        remaining,
                        remaining == 1 ? "" : "а",
                        total - remaining,
                        total);
    }

    public static String perkGranted(PerkType perk, int perksLeft) {
        String tail = perksLeft > 0
                ? "\n\n<i>Осталось выбрать ещё <b>" + perksLeft + "</b> — не уходите с пустыми руками.</i>"
                : "\n\n✅ <b>Все бонусы активны.</b> Пришлите ссылку на канал или перешлите пост для разбора.";
        return "✨ <b>" + TgHtml.esc(perk.label()) + "</b> — ваш.\n\n" + TgHtml.esc(perk.hook()) + tail;
    }

    public static String perkComingSoon(PerkType perk) {
        return """
                ✨ <b>%s</b> — активирован.

                Мы подключаем модуль к вашему аккаунту. Как только будет готов — бот напишет первым.

                <i>А пока: разбор поста и жёсткий аудит уже работают.</i>"""
                .formatted(TgHtml.esc(perk.label()));
    }

    public static String perkLockedUpsell() {
        return """
                🔒 <b>Это бонус пакета</b>

                В бесплатном режиме — 3 идеи и разбор.
                В пакете — до <b>30 запросов</b> и бонусы: разбор постов, жёсткий аудит, дайджест, конкуренты…

                <i>Один сильный пост часто окупает «Старт» с первого раза.</i>""";
    }

    public static String postAuditIntro() {
        return """
                🔬 <b>Разбор поста</b>

                Перешлите сюда <b>любой опубликованный пост</b> (из своего или чужого канала).

                Разберу:
                • цепляет ли первая строка
                • где теряется внимание
                • что усилить — одним движением""";
    }

    public static String postAuditGenerating() {
        return "🔬 <b>Разбираю пост…</b>\n\n<i>30–40 секунд — смотрю hook, структуру и сигналы вовлечения.</i>";
    }

    public static String hardAuditGenerating() {
        return "🔥 <b>Жёсткий аудит…</b>\n\n<i>Без комплиментов. Только дыры и что с ними делать.</i>";
    }

    public static String hardAuditLocked() {
        return """
                🔥 <b>Жёсткий аудит</b> — в бонусах пакета

                Это режим «без сахара»: где вы реально теряете охват и почему.

                <i>Выберите бонус при покупке или возьмите пакет «Старт» — 1 бонус на выбор.</i>""";
    }

    public static String upsellAfterFreeAnalysis() {
        return """
                ━━━━━━━━━━━━━━━
                💡 <b>Вы увидели потенциал.</b>

                Бесплатно — разбор и 3 идеи.
                В пакете — <b>12 идей</b>, <b>7 постов</b> и бонусы на выбор (разбор постов, жёсткий аудит…).

                <i>Следующий пост можно не писать с нуля — и не гадать, зайдёт или нет.</i>""";
    }

    private static String perkWord(int count) {
        if (count == 1) {
            return "";
        }
        if (count >= 2 && count <= 4) {
            return "а";
        }
        return "ов";
    }
}
