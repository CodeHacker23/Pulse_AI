package org.example.pulse_ai.telegram;

import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.handler.UpdateHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.example.pulse_ai.config.TelegramBotProperties;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class ChannelPulseBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final UpdateHandler updateHandler;

    public ChannelPulseBot(
            TelegramBotProperties properties,
            TelegramBotOptionsFactory optionsFactory,
            @Lazy UpdateHandler updateHandler
    ) {
        super(optionsFactory.create(), requireToken(properties.getToken()));
        this.botUsername = requireUsername(properties.getUsername());
        this.updateHandler = updateHandler;
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "telegram.bot.token пустой. Заполните application-local.yaml в корне проекта."
            );
        }
        return token.trim();
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("telegram.bot.username пустой.");
        }
        return username.trim();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            updateHandler.handle(update);
        } catch (Throwable ex) {
            // Важно: NoClassDefFoundError и др. Error — иначе умирает Telegram Executor и кнопки «молчат»
            log.error("Ошибка обработки update", ex);
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }
}
