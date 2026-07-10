package org.example.pulse_ai.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramBotStartup {

    private final ChannelPulseBot bot;
    private final TelegramConnectivityChecker connectivityChecker;

    public TelegramBotStartup(ChannelPulseBot bot, TelegramConnectivityChecker connectivityChecker) {
        this.bot = bot;
        this.connectivityChecker = connectivityChecker;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void registerTelegramBot() {
        connectivityChecker.verifyOrWarn();
        clearWebhook();

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            log.info("Telegram-бот @{} зарегистрирован, ожидаем сообщения", bot.getBotUsername());
        } catch (TelegramApiException e) {
            String details = e.getMessage() != null ? e.getMessage() : e.toString();
            if (details.contains("409") || details.contains("Conflict")) {
                log.error("""
                        Не удалось зарегистрировать Telegram-бота: токен уже занят другим процессом.
                        1) Закройте все другие bootRun / java-процессы с этим ботом
                        2) Подождите 10 секунд
                        3) Запустите снова: .\\gradlew.bat bootRun
                        Детали: {}""", details);
            } else {
                log.error("Не удалось зарегистрировать Telegram-бота: {}", details, e);
            }
            throw new IllegalStateException("Telegram bot registration failed: " + details, e);
        }
    }

    private void clearWebhook() {
        try {
            bot.execute(DeleteWebhook.builder().dropPendingUpdates(true).build());
            log.info("Telegram webhook сброшен, используем long polling");
        } catch (TelegramApiException e) {
            log.warn("Не удалось сбросить webhook (продолжаем): {}", e.getMessage());
        }
    }
}
