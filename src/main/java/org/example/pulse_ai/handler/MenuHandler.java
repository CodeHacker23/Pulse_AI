package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuHandler {

    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;

    public void showWelcome(long chatId, UserEntity user) {
        sessionService.setState(chatId, BotState.CONNECT_CHANNEL);
        messageSender.sendTextWithInline(chatId, BotMessages.WELCOME, keyboards.welcomeInline());
    }

    public void showMainMenu(long chatId, UserEntity user) {
        sessionService.resetToMainMenu(chatId);
        String channelTitle = userService.findActiveChannel(user)
                .map(ChannelEntity::getTitle)
                .orElse(null);
        messageSender.sendText(
                chatId,
                BotMessages.mainMenu(channelTitle, user.getBalance(), billingProperties.isEnabled()),
                keyboards.mainMenuKeyboard(billingProperties.isEnabled())
        );
        messageSender.sendTextWithInline(
                chatId,
                "⚡ Быстрые действия:",
                keyboards.mainMenuInline(user.getTotalRequests(), billingProperties.isEnabled())
        );
    }

    public void showHowItWorks(long chatId) {
        messageSender.sendTextWithInline(chatId, BotMessages.HOW_IT_WORKS, keyboards.welcomeInline());
    }

    public void showWhatInRequest(long chatId) {
        messageSender.sendTextWithInline(
                chatId,
                """
                        📦 1 запрос = полный цикл

                        ✅ Анализ канала: топ/худшие посты, время, темы
                        ✅ 8–12 идей контента с обоснованием
                        ✅ 5–7 готовых постов с заголовками и CTA
                        ✅ Публикация поста в канал через бота

                        Пакеты:
                        • Старт — 10 запросов — 990 ₽
                        • Контент — 18 запросов — 1 600 ₽ ⭐
                        • Про — 30 запросов — 2 300 ₽""",
                keyboards.paymentPackagesInline()
        );
    }

    public void showHelp(long chatId) {
        messageSender.sendText(chatId, BotMessages.HELP, keyboards.mainMenuKeyboard());
    }

    public void showBalance(long chatId, UserEntity user) {
        messageSender.sendTextWithInline(
                chatId,
                BotMessages.balance(user.getBalance(), billingProperties.isEnabled()),
                keyboards.paymentPackagesInline(billingProperties.isEnabled())
        );
    }
}
