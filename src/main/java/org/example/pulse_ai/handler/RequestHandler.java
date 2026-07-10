package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.analysis.AnalysisRequestService;
import org.example.pulse_ai.domain.analysis.AnalysisWorker;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RequestHandler {

    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final MenuHandler menuHandler;
    private final AnalysisRequestService analysisRequestService;
    private final AnalysisWorker analysisWorker;
    private final PulseBillingProperties billingProperties;

    public void startFreeAnalysis(long chatId, UserEntity user) {
        startAnalysis(chatId, user);
    }

    public void startPaidRequest(long chatId, UserEntity user) {
        if (!billingProperties.isEnabled()) {
            startAnalysis(chatId, user);
            return;
        }
        ChannelEntity channel = userService.findActiveChannel(user).orElse(null);
        if (channel == null) {
            messageSender.sendTextSafe(chatId, "Сначала подключите канал.", keyboards.mainMenuKeyboard());
            return;
        }
        if (user.getBalance() <= 0) {
            messageSender.sendTextWithInlineSafe(
                    chatId,
                    """
                            💰 Запросов не осталось

                            Чтобы сделать новый анализ, купите пакет запросов.

                            Вы уже сделали %d запросов.""".formatted(user.getTotalRequests()),
                    keyboards.paymentPackagesInline()
            );
            return;
        }

        messageSender.sendTextWithInlineSafe(
                chatId,
                """
                        📊 Новый запрос

                        Будет списан 1 запрос.
                        Останется: %d запросов.

                        Запустить?""".formatted(user.getBalance() - 1),
                keyboards.inlineConfirm(CallbackData.REQ_CONFIRM, CallbackData.MENU_MAIN)
        );
    }

    public void confirmPaidRequest(long chatId, UserEntity user) {
        startAnalysis(chatId, user);
    }

    private void startAnalysis(long chatId, UserEntity user) {
        ChannelEntity channel = userService.findActiveChannel(user).orElse(null);
        if (channel == null) {
            messageSender.sendTextSafe(chatId, "Сначала подключите канал.", keyboards.mainMenuKeyboard());
            return;
        }

        if (billingProperties.isEnabled() && user.isFreeAnalysisUsed() && user.getBalance() <= 0) {
            messageSender.sendTextWithInlineSafe(
                    chatId,
                    "Бесплатный анализ уже был. Купите пакет для полного разбора.",
                    keyboards.paymentPackagesInline()
            );
            return;
        }

        try {
            sessionService.setState(chatId, BotState.REQUEST_RUNNING);

            AnalysisRequestEntity request = billingProperties.isEnabled() && !user.isFreeAnalysisUsed()
                    ? analysisRequestService.startFree(user, channel)
                    : analysisRequestService.startAnalysis(user, channel);

            analysisWorker.runAsync(request.getId(), chatId);
        } catch (IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, org.example.pulse_ai.text.TgHtml.esc(ex.getMessage()));
            sessionService.resetToMainMenu(chatId);
            menuHandler.showMainMenu(chatId, user);
        }
    }
}
