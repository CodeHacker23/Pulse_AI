package org.example.pulse_ai.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMemberCount;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;

@Slf4j
@Service
public class TelegramBotApiService {

    private final AbsSender bot;
    private Long cachedBotId;

    public TelegramBotApiService(ObjectProvider<ChannelPulseBot> botProvider) {
        this.bot = botProvider.getIfAvailable();
    }

    public Optional<Chat> getChat(long telegramChatId) {
        if (bot == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(bot.execute(GetChat.builder().chatId(telegramChatId).build()));
        } catch (TelegramApiException ex) {
            log.warn("getChat failed for {}: {}", telegramChatId, ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Chat> getChatByUsername(String username) {
        if (bot == null) {
            return Optional.empty();
        }
        String chatId = username.startsWith("@") ? username : "@" + username;
        try {
            return Optional.of(bot.execute(GetChat.builder().chatId(chatId).build()));
        } catch (TelegramApiException ex) {
            log.warn("getChat failed for {}: {}", chatId, ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Integer> getMemberCount(long telegramChatId) {
        if (bot == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(bot.execute(GetChatMemberCount.builder().chatId(telegramChatId).build()));
        } catch (TelegramApiException ex) {
            log.warn("getChatMemberCount failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public BotAdminStatus verifyBotIsAdmin(long telegramChatId) {
        if (bot == null) {
            return new BotAdminStatus(false, false, false, "Бот не запущен");
        }
        try {
            Long botId = resolveBotId();
            ChatMember member = bot.execute(GetChatMember.builder()
                    .chatId(telegramChatId)
                    .userId(botId)
                    .build());

            if (member instanceof ChatMemberAdministrator admin) {
                return new BotAdminStatus(
                        true,
                        Boolean.TRUE.equals(admin.getCanPostMessages()),
                        Boolean.TRUE.equals(admin.getCanPostMessages()),
                        null
                );
            }
            return new BotAdminStatus(false, false, false,
                    "Добавьте бота администратором канала с правом публикации.");
        } catch (TelegramApiException ex) {
            return new BotAdminStatus(false, false, false, ex.getMessage());
        }
    }

    private Long resolveBotId() throws TelegramApiException {
        if (cachedBotId != null) {
            return cachedBotId;
        }
        if (bot instanceof ChannelPulseBot channelBot) {
            cachedBotId = channelBot.execute(GetMe.builder().build()).getId();
            return cachedBotId;
        }
        throw new TelegramApiException("Bot id unavailable");
    }

    public record BotAdminStatus(
            boolean isAdmin,
            boolean canPost,
            boolean canViewStats,
            String errorMessage
    ) {
    }
}
