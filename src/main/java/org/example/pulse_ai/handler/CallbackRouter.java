package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CallbackRouter {

    private final MenuHandler menuHandler;
    private final ChannelHandler channelHandler;
    private final RequestHandler requestHandler;
    private final PaymentHandler paymentHandler;
    private final HistoryHandler historyHandler;
    private final ResultCallbackHandler resultCallbackHandler;
    private final PublishHandler publishHandler;
    private final ProductChannelHandler productChannelHandler;
    private final PerkHandler perkHandler;
    private final FeatureHandler featureHandler;
    private final ManagerHandler managerHandler;
    private final PollHandler pollHandler;
    private final TelegramMessageSender messageSender;

    public void route(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        if (callbackData.equals(CallbackData.MENU_MAIN)) {
            messageSender.answerCallback(callbackQueryId);
            menuHandler.showMainMenu(chatId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT)) {
            resultCallbackHandler.handle(chatId, messageId, callbackQueryId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PERK)) {
            perkHandler.handle(chatId, messageId, callbackQueryId, user, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_FEAT)) {
            featureHandler.handle(chatId, messageId, callbackQueryId, user, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH)
                || callbackData.startsWith(CallbackData.PREFIX_SCHEDULE)) {
            publishHandler.handle(chatId, messageId, callbackQueryId, user, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PRODUCT)) {
            productChannelHandler.handle(chatId, messageId, callbackQueryId, user, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_AGENT)) {
            managerHandler.handle(chatId, messageId, callbackQueryId, user, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_POLL)) {
            pollHandler.handle(chatId, messageId, callbackQueryId, user, callbackData);
            return;
        }
        route(chatId, user, callbackData);
    }

    public void route(long chatId, UserEntity user, String callbackData) {
        if (callbackData.equals(CallbackData.MENU_MAIN)) {
            menuHandler.showMainMenu(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_HOW_IT_WORKS)) {
            menuHandler.showHowItWorks(chatId);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_WHAT_IN_REQUEST)) {
            menuHandler.showWhatInRequest(chatId);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_CONTENT)) {
            menuHandler.showContentHub(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_ANALYTICS)) {
            menuHandler.showAnalyticsHub(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_SETTINGS)) {
            menuHandler.showSettings(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_HELP)) {
            menuHandler.showHelp(chatId);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_MORE)) {
            menuHandler.showMore(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_TIMEZONE)) {
            menuHandler.showTimezonePicker(chatId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.MENU_TIMEZONE_SET)) {
            String zoneId = callbackData.substring(CallbackData.MENU_TIMEZONE_SET.length());
            menuHandler.setTimezone(chatId, user, zoneId);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_ANALYTICS_PLUS)) {
            menuHandler.showAnalyticsPlus(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_GROWTH)) {
            menuHandler.showGrowth(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_STYLE_PROMPT)) {
            menuHandler.showStylePrompt(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_STYLE_PROMPT_SET)) {
            menuHandler.promptStyleInput(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.MENU_STYLE_PROMPT_CLEAR)) {
            menuHandler.clearStylePrompt(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.CHANNEL_CONNECT)
                || callbackData.equals(CallbackData.CHANNEL_CONNECT_LIMITED)) {
            channelHandler.showConnectInstructions(chatId);
            return;
        }
        if (callbackData.equals(CallbackData.CHANNEL_CONNECT_PUBLISH)) {
            channelHandler.showPublishConnectInstructions(chatId);
            return;
        }
        if (callbackData.equals(CallbackData.REQ_FREE)) {
            requestHandler.startFreeAnalysis(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.REQ_START)) {
            requestHandler.startPaidRequest(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.REQ_CONFIRM)) {
            requestHandler.confirmPaidRequest(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.PAY_SELECT)) {
            paymentHandler.showPackages(chatId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PAY)) {
            paymentHandler.selectPackage(chatId, user, callbackData);
            return;
        }
        if (callbackData.equals(CallbackData.PREFIX_HIST + "list")) {
            historyHandler.showHistory(chatId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_HIST + "open:")) {
            long requestId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_HIST + "open:").length()));
            historyHandler.openReport(chatId, 0, user, requestId);
            return;
        }

        menuHandler.showMainMenu(chatId, user);
    }

    public void routeHistory(long chatId, int messageId, UserEntity user, String callbackData) {
        if (callbackData.startsWith(CallbackData.PREFIX_HIST + "open:")) {
            long requestId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_HIST + "open:").length()));
            historyHandler.openReport(chatId, messageId, user, requestId);
        }
    }
}
