package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.analysis.AnalysisRequestService;
import org.example.pulse_ai.domain.channel.ChannelService;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UpdateHandler {

    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final MenuHandler menuHandler;
    private final ChannelHandler channelHandler;
    private final ChannelService channelService;
    private final PaymentHandler paymentHandler;
    private final CallbackRouter callbackRouter;
    private final ChannelSyncService channelSyncService;
    private final AnalysisRequestService analysisRequestService;
    private final PublishHandler publishHandler;
    private final ProductChannelHandler productChannelHandler;
    private final FeatureHandler featureHandler;
    private final ManagerHandler managerHandler;
    private final AdRadarHandler adRadarHandler;
    private final AdDealHandler adDealHandler;
    private final OutreachHandler outreachHandler;
    private final PollHandler pollHandler;
    private final ScoutAdminHandler scoutAdminHandler;
    private final org.example.pulse_ai.domain.lead.CommentLeadAgent commentLeadAgent;

    public void handle(Update update) {
        if (update.hasPreCheckoutQuery()) {
            handlePreCheckout(update);
            return;
        }
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        if (update.hasChannelPost()) {
            channelSyncService.ingestLiveChannelPost(update.getChannelPost());
            return;
        }
        if (update.hasEditedChannelPost()) {
            channelSyncService.ingestLiveChannelPost(update.getEditedChannelPost());
            return;
        }
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        if (message.getChat() != null
                && (message.getChat().isGroupChat() || message.getChat().isSuperGroupChat())) {
            commentLeadAgent.handleGroupMessage(message);
            return;
        }
        if (message.getFrom() == null) {
            return;
        }
        long chatId = message.getChatId();
        UserEntity user = userService.findOrCreate(message.getFrom());

        if (message.getForwardFromChat() != null) {
            UserSession session = sessionService.getOrCreate(chatId);
            syncSessionWithDb(chatId, user, session);
            if (session.getState() == BotState.CONNECT_CHANNEL) {
                handleConnectInput(chatId, user, message);
                return;
            }
            Optional<ChannelEntity> active = userService.findActiveChannel(user);
            if (active.isPresent()
                    && active.get().getTelegramChatId().equals(message.getForwardFromChat().getId())) {
                handleConnectInput(chatId, user, message);
                return;
            }
            if ("channel".equals(message.getForwardFromChat().getType())) {
                handleConnectInput(chatId, user, message);
                return;
            }
            if (message.hasText() || (message.getCaption() != null && !message.getCaption().isBlank())) {
                featureHandler.handleForwardedPost(chatId, user, message);
                return;
            }
            messageSender.sendText(chatId, "Перешлите пост с текстом — или любой пост из своего канала, если бот там админ.");
            return;
        }
        if (message.hasText()) {
            handleText(chatId, user, message.getText().trim(), message);
            return;
        }
        if (message.hasSuccessfulPayment()) {
            paymentHandler.handleSuccessfulPayment(user, message.getSuccessfulPayment());
        }
    }

    private void handlePreCheckout(Update update) {
        var query = update.getPreCheckoutQuery();
        if (query.getFrom() == null) {
            return;
        }
        paymentHandler.handlePreCheckout(
                query.getId(),
                query.getInvoicePayload(),
                query.getFrom().getId()
        );
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() == null || callbackQuery.getFrom() == null) {
            return;
        }

        long chatId = callbackQuery.getMessage().getChatId();
        int messageId = callbackQuery.getMessage().getMessageId();
        UserEntity user = userService.findOrCreate(callbackQuery.getFrom());
        syncSessionWithDb(chatId, user, sessionService.getOrCreate(chatId));

        String data = callbackQuery.getData();
        if (data != null && (data.startsWith(CallbackData.PREFIX_RESULT)
                || data.startsWith(CallbackData.PREFIX_PUBLISH)
                || data.startsWith(CallbackData.PREFIX_SCHEDULE)
                || data.startsWith(CallbackData.PREFIX_PRODUCT)
                || data.startsWith(CallbackData.PREFIX_PERK)
                || data.startsWith(CallbackData.PREFIX_FEAT)
                || data.startsWith(CallbackData.PREFIX_AGENT)
                || data.startsWith(CallbackData.PREFIX_POLL)
                || data.startsWith(CallbackData.PREFIX_HIST + "open:")
                || CallbackData.MENU_MAIN.equals(data))) {
            if (data.startsWith(CallbackData.PREFIX_HIST + "open:")) {
                messageSender.answerCallback(callbackQuery.getId());
                callbackRouter.routeHistory(chatId, messageId, user, data);
                return;
            }
            if (CallbackData.MENU_MAIN.equals(data)) {
                callbackRouter.route(chatId, messageId, callbackQuery.getId(), user, data);
                return;
            }
            callbackRouter.route(chatId, messageId, callbackQuery.getId(), user, data);
            return;
        }

        messageSender.answerCallback(callbackQuery.getId());
        callbackRouter.route(chatId, user, data);
    }

    private void handleText(long chatId, UserEntity user, String text, Message message) {
        UserSession session = sessionService.getOrCreate(chatId);
        syncSessionWithDb(chatId, user, session);

        if (isRunningAnalysis(session)) {
            messageSender.sendText(chatId, BotMessages.ANALYSIS_IN_PROGRESS);
            return;
        }

        if (MenuText.CMD_START.equals(text) || MenuText.CMD_MENU.equals(text)) {
            showEntry(chatId, user);
            return;
        }
        if (MenuText.CMD_CANCEL.equals(text)) {
            sessionService.resetToMainMenu(chatId);
            menuHandler.showMainMenu(chatId, user);
            return;
        }
        if (MenuText.CMD_SCOUT.equals(text)) {
            if (scoutAdminHandler.canViewScout(user)) {
                scoutAdminHandler.showStatus(chatId, 0, user);
            } else {
                messageSender.sendText(chatId, "🔒 Команда только для админов Pulse.");
            }
            return;
        }

        // Состояния ввода — раньше кнопок меню, иначе текст поста/времени теряется.
        if (session.getState() == BotState.POST_EDIT) {
            String md = org.example.pulse_ai.text.TgMarkdown.fromMessage(message);
            if (md.isBlank()) {
                messageSender.sendText(chatId, "Отправьте текст поста одним сообщением.");
                return;
            }
            publishHandler.handleEditedText(chatId, user, md);
            return;
        }
        if (session.getState() == BotState.PRODUCT_EDIT) {
            String md = org.example.pulse_ai.text.TgMarkdown.fromMessage(message);
            if (md.isBlank()) {
                messageSender.sendText(chatId, "Отправьте текст поста одним сообщением.");
                return;
            }
            productChannelHandler.handleEditedText(chatId, user, md);
            return;
        }
        if (session.getState() == BotState.SCHEDULE_TIME_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите дату и время: ДД.ММ ЧЧ:ММ (МСК).");
                return;
            }
            publishHandler.handleScheduleTimeInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.COMPETITOR_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите ссылку на канал конкурента.");
                return;
            }
            featureHandler.handleCompetitorInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.SELLING_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Опишите, что продаём — одним сообщением.");
                return;
            }
            featureHandler.handleSellingInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AGENT_REPLY_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Напишите текст ответа клиенту одним сообщением.");
                return;
            }
            managerHandler.handleCustomReplyInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AGENT_FAQ_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите факты для ответов (цены, доставка, оффер).");
                return;
            }
            managerHandler.handleFaqInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AGENT_OBJECTIONS_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите формулировки возражений одним сообщением.");
                return;
            }
            managerHandler.handleObjectionsInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AD_RADAR_WATCH_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите ссылку на чат или @username.");
                return;
            }
            adRadarHandler.handleWatchInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AD_RADAR_PLACE_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите @username публичного канала.");
                return;
            }
            adRadarHandler.handlePlaceInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.OUTREACH_SOURCE_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите @username или ссылку на группу.");
                return;
            }
            outreachHandler.handleSourceInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.OUTREACH_MESSAGE_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Напишите текст первого сообщения.");
                return;
            }
            outreachHandler.handleMessageInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.OUTREACH_IMPORT_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите @username (каждый с новой строки).");
                return;
            }
            outreachHandler.handleImportInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AUDIENCE_PARSE_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите ссылку на группу или @chat.");
                return;
            }
            outreachHandler.handleParseLinkInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.POLL_OPTIONS_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите варианты по одному на строку (минимум 2).");
                return;
            }
            pollHandler.handleCustomOptionsInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.STYLE_PROMPT_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришлите промпт стиля одним сообщением.");
                return;
            }
            menuHandler.handleStylePromptInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.AD_DEAL_PRICE_INPUT) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Введите цену числом, например 2500");
                return;
            }
            adDealHandler.handlePriceInput(chatId, user, text.trim());
            return;
        }
        if (session.getState() == BotState.PRODUCT_RELEASE_ADD) {
            if (text.isBlank()) {
                messageSender.sendText(chatId, "Пришли текст апдейта (версия + буллеты).");
                return;
            }
            productChannelHandler.handleReleaseInput(chatId, user, text);
            return;
        }

        if (MenuText.CMD_HELP.equals(text) || MenuText.BTN_HELP.equals(text)) {
            menuHandler.showHelp(chatId);
            return;
        }
        if (MenuText.BTN_HOW.equals(text)) {
            menuHandler.showHowItWorks(chatId);
            return;
        }
        if (MenuText.BTN_CONNECT_PUBLISH.equals(text)) {
            channelHandler.showPublishConnectInstructions(chatId);
            return;
        }
        if (MenuText.BTN_BUY.equals(text)) {
            paymentHandler.showPackages(chatId, user);
            return;
        }
        if (MenuText.CMD_PRODUCT.equals(text)) {
            productChannelHandler.showMenu(chatId, user);
            return;
        }
        if (MenuText.CMD_BALANCE.equals(text)) {
            menuHandler.showBalance(chatId, user);
            return;
        }
        if (MenuText.CMD_HISTORY.equals(text) || MenuText.BTN_REPORTS.equals(text)
                || MenuText.BTN_ANALYTICS.equals(text)) {
            menuHandler.showAnalyticsHub(chatId, user);
            return;
        }
        if (MenuText.BTN_CONTENT.equals(text)) {
            menuHandler.showContentHub(chatId, user);
            return;
        }
        if (MenuText.BTN_GROWTH.equals(text)) {
            menuHandler.showGrowth(chatId, user);
            return;
        }
        if (MenuText.BTN_MORE.equals(text)) {
            menuHandler.showMore(chatId, user);
            return;
        }
        if (MenuText.BTN_STYLE_PROMPT.equals(text)) {
            menuHandler.showStylePrompt(chatId, user);
            return;
        }
        if (MenuText.CMD_SCHEDULED.equals(text) || MenuText.BTN_SCHEDULED.equals(text)) {
            publishHandler.openScheduledList(chatId, user);
            return;
        }
        if (MenuText.CMD_MANAGER.equals(text)
                || MenuText.BTN_ASSISTANT.equals(text)
                || MenuText.BTN_MANAGER_LEGACY.equals(text)) {
            managerHandler.open(chatId, user);
            return;
        }
        if (MenuText.CMD_CHANNEL.equals(text) || MenuText.BTN_SETTINGS.equals(text)) {
            menuHandler.showSettings(chatId, user);
            return;
        }
        if (MenuText.BTN_ANALYZE.equals(text)) {
            if (userService.findActiveChannel(user).isPresent()) {
                if (analysisRequestService.hasActiveRequest(user.getId())) {
                    messageSender.sendText(chatId, BotMessages.ANALYSIS_IN_PROGRESS);
                } else {
                    callbackRouter.route(chatId, user, CallbackData.REQ_FREE);
                }
            } else {
                messageSender.sendText(chatId, "Пришлите ссылку на канал в чат — например t.me/durov");
            }
            return;
        }

        if (session.getState() == BotState.CONNECT_CHANNEL || looksLikeChannelLink(text)) {
            handleConnectInput(chatId, user, message);
            return;
        }

        menuHandler.showMainMenu(chatId, user);
    }

    private void showEntry(long chatId, UserEntity user) {
        if (userService.findActiveChannel(user).isPresent()) {
            menuHandler.showMainMenu(chatId, user);
        } else {
            menuHandler.showWelcome(chatId, user);
        }
    }

    private void handleConnectInput(long chatId, UserEntity user, Message message) {
        if (message.getForwardFromChat() != null) {
            Optional<ChannelEntity> active = userService.findActiveChannel(user);
            if (active.isPresent()
                    && active.get().getTelegramChatId().equals(message.getForwardFromChat().getId())) {
                channelService.ingestPost(active.get(), message);
                messageSender.sendText(chatId, "✅ Пост обновлён в базе аналитики.");
                return;
            }
        }
        channelHandler.handleConnectInput(chatId, user, message);
    }

    private void syncSessionWithDb(long chatId, UserEntity user, UserSession session) {
        if (isRunningAnalysis(session) && !analysisRequestService.hasActiveRequest(user.getId())) {
            sessionService.resetToMainMenu(chatId);
        }
    }

    private static boolean isRunningAnalysis(UserSession session) {
        return session.getState() == BotState.FREE_ANALYSIS_RUNNING
                || session.getState() == BotState.REQUEST_RUNNING;
    }

    private static boolean looksLikeChannelLink(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim().toLowerCase();
        return t.startsWith("@")
                || t.contains("t.me/")
                || t.contains("telegram.me/");
    }
}
