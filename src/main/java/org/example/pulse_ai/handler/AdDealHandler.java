package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.radar.AdDealService;
import org.example.pulse_ai.domain.radar.AdDealStatuses;
import org.example.pulse_ai.domain.radar.AdPinFormats;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AdDealEntity;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AdPlacementRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.Optional;

/**
 * Воронка сделки: формат → креатив → бриф админу → AGREED/REJECTED.
 */
@Component
@RequiredArgsConstructor
public class AdDealHandler {

    private final AdDealService dealService;
    private final AdPlacementRepository placementRepository;
    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void handle(long chatId, int messageId, String callbackData, UserEntity user) {
        if (callbackData.equals(CallbackData.AGENT_DEAL_LIST)) {
            showList(chatId, messageId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_OPEN)) {
            long placementId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_OPEN.length()));
            openDeal(chatId, messageId, user, placementId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_FMT)) {
            // agent:deal:fmt:<dealId>:<FORMAT>
            String rest = callbackData.substring(CallbackData.AGENT_DEAL_FMT.length());
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return;
            }
            long dealId = Long.parseLong(rest.substring(0, colon));
            String fmt = rest.substring(colon + 1);
            setFormat(chatId, messageId, user, dealId, fmt);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_VIEW)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_VIEW.length()));
            showDeal(chatId, messageId, user, dealId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_CREATIVE)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_CREATIVE.length()));
            makeCreative(chatId, messageId, user, dealId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_BRIEF)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_BRIEF.length()));
            showBrief(chatId, messageId, user, dealId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_SENT)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_SENT.length()));
            markSent(chatId, messageId, user, dealId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_OK)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_OK.length()));
            markAgreed(chatId, messageId, user, dealId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_NO)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_NO.length()));
            markRejected(chatId, messageId, user, dealId);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_DEAL_PRICE)) {
            long dealId = Long.parseLong(callbackData.substring(CallbackData.AGENT_DEAL_PRICE.length()));
            promptPrice(chatId, user, dealId);
            return;
        }
        showList(chatId, messageId, user);
    }

    public void openDeal(long chatId, int messageId, UserEntity user, long placementId) {
        Optional<ChannelEntity> ownerOpt = userService.findActiveChannel(user);
        AdPlacementEntity placement = placementRepository.findByIdAndUserId(placementId, user.getId()).orElse(null);
        if (ownerOpt.isEmpty() || placement == null) {
            editOrSend(chatId, messageId,
                    "Нужен ваш канал (разбор) и площадка из поиска.",
                    keyboards.adRadarMenuInline());
            return;
        }
        AdDealEntity deal = dealService.openOrGet(user, ownerOpt.get(), placement);
        showFormatPicker(chatId, messageId, deal);
    }

    private void showFormatPicker(long chatId, int messageId, AdDealEntity deal) {
        String text = """
                📋 <b>Сделка #%d</b> · @%s

                Выберите формат размещения.""".formatted(
                deal.getId(),
                TgHtml.esc(deal.getTargetUsername()));
        editOrSend(chatId, messageId, text, keyboards.adDealFormatInline(deal.getId()));
    }

    private void setFormat(long chatId, int messageId, UserEntity user, long dealId, String fmt) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            messageSender.sendTextSafe(chatId, "Сделка не найдена.");
            return;
        }
        deal = dealService.setFormat(deal, fmt);
        showDeal(chatId, messageId, user, deal.getId());
    }

    public void showDeal(long chatId, int messageId, UserEntity user, long dealId) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            messageSender.sendTextSafe(chatId, "Сделка не найдена.");
            return;
        }
        int admin = deal.getPriceAdminRub() != null ? deal.getPriceAdminRub() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>Сделка #").append(deal.getId()).append("</b>\n");
        sb.append("Площадка: <b>@").append(TgHtml.esc(deal.getTargetUsername())).append("</b>\n");
        sb.append("Статус: <b>").append(AdDealStatuses.label(deal.getStatus())).append("</b>\n");
        sb.append("Формат: ").append(TgHtml.esc(AdPinFormats.label(deal.getPinFormat()))).append('\n');
        if (admin > 0) {
            sb.append("Цена админа: ~<b>").append(formatNum(admin)).append(" ₽</b>\n");
        }
        if (deal.getCreativeDraft() != null && !deal.getCreativeDraft().isBlank()) {
            sb.append("\n✍️ Креатив:\n").append(TgHtml.fromMarkdown(
                    shorten(deal.getCreativeDraft(), 500))).append('\n');
        } else {
            sb.append("\n<i>Креатив ещё не сгенерирован.</i>\n");
        }
        if (deal.getAdminNotes() != null && !deal.getAdminNotes().isBlank()) {
            sb.append("\n📝 Заметки: <i>").append(TgHtml.esc(shorten(deal.getAdminNotes(), 200)))
                    .append("</i>\n");
        }
        editOrSend(chatId, messageId, sb.toString().trim(), keyboards.adDealCardInline(deal));
    }

    private void makeCreative(long chatId, int messageId, UserEntity user, long dealId) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            return;
        }
        Optional<ChannelEntity> ownerOpt = userService.findActiveChannel(user);
        AdPlacementEntity placement = dealService.placementOf(deal).orElse(null);
        if (ownerOpt.isEmpty() || placement == null) {
            messageSender.sendTextSafe(chatId, "Нужен канал и площадка.");
            return;
        }
        editOrSend(chatId, messageId, "⏳ Пишу креатив…", null);
        String draft = dealService.generateCreative(ownerOpt.get(), placement);
        dealService.attachCreative(deal, draft);
        showDeal(chatId, messageId, user, dealId);
    }

    private void showBrief(long chatId, int messageId, UserEntity user, long dealId) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            return;
        }
        ChannelEntity owner = userService.findActiveChannel(user).orElse(null);
        AdPlacementEntity placement = dealService.placementOf(deal).orElse(null);
        String brief = dealService.buildAdminBrief(deal, owner, placement);
        String text = "📨 <b>Бриф для админа @" + TgHtml.esc(deal.getTargetUsername()) + "</b>\n\n"
                + "Скопируйте и отправьте в ЛС / бота админа площадки:\n\n"
                + "<pre>" + TgHtml.esc(shorten(brief, 3200)) + "</pre>\n\n"
                + "Когда отправите — нажмите «Отправил админу».";
        // pre may fail if too long — fallback
        try {
            editOrSend(chatId, messageId, text, keyboards.adDealBriefInline(dealId));
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "📨 Бриф:\n\n" + brief);
            editOrSend(chatId, messageId,
                    "Бриф отправлен отдельным сообщением. Нажмите «Отправил админу», когда уйдёт.",
                    keyboards.adDealBriefInline(dealId));
        }
        // Also send plain for easy forward
        messageSender.sendTextSafe(chatId, brief);
    }

    private void markSent(long chatId, int messageId, UserEntity user, long dealId) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            return;
        }
        dealService.markSentToAdmin(deal);
        editOrSend(chatId, messageId,
                "✅ Статус: <b>ждём админа</b> (сделка #" + dealId + ").\n\n"
                        + "Когда ответит — внесите цену или отметьте «Согласовано» / «Отказ».",
                keyboards.adDealAwaitingInline(dealId));
    }

    private void markAgreed(long chatId, int messageId, UserEntity user, long dealId) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            return;
        }
        dealService.markAgreed(deal, null);
        showDeal(chatId, messageId, user, dealId);
    }

    private void markRejected(long chatId, int messageId, UserEntity user, long dealId) {
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            return;
        }
        dealService.markRejected(deal, "Отказ админа / не по тематике");
        showDeal(chatId, messageId, user, dealId);
    }

    private void promptPrice(long chatId, UserEntity user, long dealId) {
        if (dealService.findOwned(dealId, user.getId()).isEmpty()) {
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setAdDealId(dealId);
        session.setState(BotState.AD_DEAL_PRICE_INPUT);
        messageSender.sendText(chatId,
                "Введите цену админа площадки числом в рублях (например <code>2500</code>).\n"
                        + "Вам покажем её + " + AdDealService.COMMISSION_PERCENT + "%.\n"
                        + "/cancel — отмена.");
    }

    public void handlePriceInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        Long dealId = session.getAdDealId();
        if (dealId == null) {
            session.setState(BotState.MAIN_MENU);
            messageSender.sendTextSafe(chatId, "Сделка потеряна. Откройте из списка сделок.");
            return;
        }
        Integer price = parseRub(raw);
        if (price == null || price < 100) {
            messageSender.sendTextSafe(chatId, "Нужно число ≥ 100, например 2500");
            return;
        }
        AdDealEntity deal = dealService.findOwned(dealId, user.getId()).orElse(null);
        if (deal == null) {
            session.setAdDealId(null);
            session.setState(BotState.MAIN_MENU);
            return;
        }
        dealService.setAdminPrice(deal, price);
        if (AdDealStatuses.AWAITING_ADMIN.equals(deal.getStatus())
                || AdDealStatuses.BRIEF.equals(deal.getStatus())
                || AdDealStatuses.INTEREST.equals(deal.getStatus())) {
            dealService.markAgreed(deal, price);
        }
        session.setAdDealId(null);
        session.setState(BotState.MAIN_MENU);
        messageSender.sendTextWithInlineSafe(chatId,
                "✅ Цена админа: <b>" + formatNum(price) + " ₽</b>\n"
                        + "Статус: <b>согласовано</b>",
                keyboards.adDealCardInline(dealService.findOwned(dealId, user.getId()).orElse(deal)));
    }

    public void showList(long chatId, int messageId, UserEntity user) {
        List<AdDealEntity> deals = dealService.listForUser(user.getId());
        if (deals.isEmpty()) {
            editOrSend(chatId, messageId,
                    "📋 <b>Сделки</b>\n\nПока пусто. Найдите площадку → «Оформить сделку».",
                    keyboards.adRadarMenuInline());
            return;
        }
        StringBuilder sb = new StringBuilder("📋 <b>Ваши сделки</b>\n\n");
        for (AdDealEntity d : deals) {
            sb.append("#").append(d.getId())
                    .append(" · @").append(TgHtml.esc(d.getTargetUsername()))
                    .append(" · ").append(AdDealStatuses.label(d.getStatus()));
            if (d.getPriceClientRub() != null) {
                sb.append(" · ~").append(formatNum(d.getPriceClientRub())).append(" ₽");
            }
            sb.append('\n');
        }
        editOrSend(chatId, messageId, sb.toString().trim(), keyboards.adDealListInline(deals));
    }

    private static Integer parseRub(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String formatNum(int n) {
        return String.format("%,d", n).replace(',', ' ');
    }

    private static String shorten(String s, int max) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private void editOrSend(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editTextOrReplace(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }
}
