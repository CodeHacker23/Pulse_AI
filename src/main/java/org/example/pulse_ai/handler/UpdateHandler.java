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
    private final HistoryHandler historyHandler;
    private final PaymentHandler paymentHandler;
    private final CallbackRouter callbackRouter;
    private final ChannelSyncService channelSyncService;
    private final AnalysisRequestService analysisRequestService;

    public void handle(Update update) {
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
        if (!update.hasMessage() || update.getMessage().getFrom() == null) {
            return;
        }
        Message message = update.getMessage();
        long chatId = message.getChatId();
        UserEntity user = userService.findOrCreate(message.getFrom());

        if (message.getForwardFromChat() != null) {
            handleConnectInput(chatId, user, message);
            return;
        }
        if (message.hasText()) {
            handleText(chatId, user, message.getText().trim(), message);
        }
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
        if (data != null && data.startsWith(CallbackData.PREFIX_RESULT)) {
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
        if (MenuText.CMD_HELP.equals(text) || MenuText.BTN_HELP.equals(text)) {
            menuHandler.showHelp(chatId);
            return;
        }
        if (MenuText.BTN_HOW.equals(text)) {
            menuHandler.showHowItWorks(chatId);
            return;
        }
        if (MenuText.CMD_BALANCE.equals(text)) {
            menuHandler.showBalance(chatId, user);
            return;
        }
        if (MenuText.CMD_HISTORY.equals(text) || MenuText.BTN_REPORTS.equals(text)) {
            historyHandler.showHistory(chatId, user);
            return;
        }
        if (MenuText.CMD_CHANNEL.equals(text) || MenuText.BTN_SETTINGS.equals(text)
                || MenuText.BTN_ANALYZE.equals(text)) {
            channelHandler.showConnectInstructions(chatId);
            return;
        }
        if (MenuText.BTN_BUY.equals(text)) {
            paymentHandler.showPackages(chatId);
            return;
        }

        if (session.getState() == BotState.CONNECT_CHANNEL) {
            handleConnectInput(chatId, user, message);
            return;
        }
        if (session.getState() == BotState.POST_EDIT) {
            messageSender.sendText(chatId, BotMessages.FEATURE_COMING_SOON);
            sessionService.resetToMainMenu(chatId);
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
}
