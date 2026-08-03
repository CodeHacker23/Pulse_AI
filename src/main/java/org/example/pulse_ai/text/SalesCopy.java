package org.example.pulse_ai.text;

import org.example.pulse_ai.domain.entitlement.AssistantQuotaService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.payment.PackageKind;
import org.example.pulse_ai.persistence.entity.PackageEntity;

/**
 * Продающие тексты: якорение, дефицит, незавершённость, выгода vs боль.
 */
public final class SalesCopy {

    private SalesCopy() {
    }

    public static String catalogIntro(boolean subscribed, AssistantQuotaService.DmQuotaSnapshot dm) {
        StringBuilder sb = new StringBuilder();
        sb.append("💳 <b>Тарифы Pulse AI</b>\n\n");
        sb.append("<b>Разборы</b> — топливо на анализ и контент.\n");
        sb.append("<b>Ассистент</b> — лиды в комментах + квота ЛС + парсинг.\n\n");
        if (dm != null && subscribed) {
            sb.append("Ваш счётчик: ").append(dm.counterLine()).append("\n");
            sb.append("<i>~50 ₽/касание на рынке — ваша квота уже в тарифе.</i>\n\n");
        } else if (!subscribed) {
            sb.append("<i>Без подписки ассистента допы ЛС не продаём.</i>\n\n");
        }
        sb.append("Выберите пакет кнопкой ниже.");
        return sb.toString();
    }

    public static String packagesIntro() {
        return catalogIntro(false, null);
    }

    public static String packageLine(PackageEntity pack, boolean highlight) {
        PackageKind kind = PackageKind.from(pack.getKind());
        String anchor = highlight ? "⭐ " : "• ";
        return switch (kind) {
            case ASSISTANT -> assistantLine(pack, highlight);
            case LS_TOPUP -> anchor + TgHtml.b(pack.getName())
                    + " — <b>+" + pack.getDmQuota() + "</b> ЛС\n"
                    + "   " + pack.getStarsAmount() + " ⭐ · " + pack.getPriceRub() + " ₽";
            case ANALYSIS -> {
                int perRequest = pack.getRequestCount() > 0
                        ? pack.getPriceRub() / pack.getRequestCount()
                        : pack.getPriceRub();
                String perks = pack.getPerkChoicesCount() > 0
                        ? " + <b>" + pack.getPerkChoicesCount() + "</b> бонус"
                        + perkWord(pack.getPerkChoicesCount()) + " на выбор"
                        : "";
                String priority = pack.isIncludesPriority() ? " + ⚡ приоритет" : "";
                yield anchor + TgHtml.b(pack.getName())
                        + " — <b>" + pack.getRequestCount() + "</b> запросов"
                        + perks + priority + "\n"
                        + "   " + pack.getStarsAmount() + " ⭐ · ~" + perRequest + " ₽/запрос";
            }
        };
    }

    private static String assistantLine(PackageEntity pack, boolean highlight) {
        String anchor = highlight ? "⭐ " : "• ";
        String find = pack.isIncludesFindAudience() ? " · Найти ЦА" : "";
        String prio = pack.isIncludesPriority() ? " · ⚡" : "";
        return anchor + TgHtml.b(pack.getName())
                + " — <b>" + pack.getDmQuota() + "</b> ЛС/мес"
                + " · парсинг " + pack.getParseQuota() + find + prio + "\n"
                + "   " + pack.getStarsAmount() + " ⭐ · " + pack.getPriceRub() + " ₽/мес";
    }

    public static String invoiceDescription(PackageEntity pack) {
        PackageKind kind = PackageKind.from(pack.getKind());
        return switch (kind) {
            case ASSISTANT -> pack.getName() + ": " + pack.getDmQuota() + " ЛС/мес · Pulse Ассистент";
            case LS_TOPUP -> "Доп. +" + pack.getDmQuota() + " ЛС · Pulse AI";
            case ANALYSIS -> pack.getRequestCount() + " запросов + " + pack.getPerkChoicesCount()
                    + " бонус" + perkWord(pack.getPerkChoicesCount()) + " на выбор · Pulse AI";
        };
    }

    public static String assistantPaymentSuccess(String packName, AssistantQuotaService.DmQuotaSnapshot dm) {
        return """
                ✅ <b>Подписка «%s» активна на 30 дней.</b>

                %s

                Откройте 🧑‍💼 Ассистент: включите ловлю лидов и запускайте рассылки.
                <i>Рынок ~50 ₽/касание — ваша квота уже внутри тарифа.</i>"""
                .formatted(TgHtml.esc(packName), dm.counterLine());
    }

    public static String lsTopupSuccess(String packName, int added, AssistantQuotaService.DmQuotaSnapshot dm) {
        return """
                ✅ <b>%s</b> зачислены.

                +<b>%d</b> ЛС к допам.
                %s"""
                .formatted(TgHtml.esc(packName), added, dm.counterLine());
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

    public static String assistantPaywall() {
        return """
                🔒 <b>Нужна подписка Pulse Ассистент</b>

                • <b>3990</b> — комменты + 100 ЛС
                • <b>6990</b> — 500 ЛС + Найти ЦА
                • <b>9990</b> — 1000 ЛС + приоритет

                Оформить — «💳 Тарифы».""";
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
