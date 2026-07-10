package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.UserEntity;
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

    public void route(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT)) {
            resultCallbackHandler.handle(chatId, messageId, callbackQueryId, callbackData);
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
        if (callbackData.equals(CallbackData.CHANNEL_CONNECT)
                || callbackData.equals(CallbackData.CHANNEL_CONNECT_LIMITED)) {
            channelHandler.showConnectInstructions(chatId);
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
            paymentHandler.showPackages(chatId);
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

        menuHandler.showMainMenu(chatId, user);
    }
}
