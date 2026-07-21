package org.example.pulse_ai.domain.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.stats.ChannelPostIngestService;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.telegram.TelegramBotApiService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final Pattern CHANNEL_USERNAME = Pattern.compile("^@?[a-zA-Z0-9_]{4,32}$");

    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ChannelPostIngestService postIngestService;
    private final ChannelSyncService channelSyncService;
    private final TelegramBotApiService botApi;

    @Transactional
    public ChannelEntity connectFromForward(UserEntity user, Message forwardedMessage) {
        if (forwardedMessage.getForwardFromChat() == null) {
            throw new ChannelConnectException("Перешлите пост из канала, а не из личного чата.");
        }
        String chatType = forwardedMessage.getForwardFromChat().getType();
        if (!"channel".equals(chatType)) {
            throw new ChannelConnectException("Перешлите пост именно из Telegram-канала.");
        }

        Long telegramChatId = forwardedMessage.getForwardFromChat().getId();
        String title = forwardedMessage.getForwardFromChat().getTitle();
        String username = forwardedMessage.getForwardFromChat().getUserName();

        ChannelEntity saved = saveChannel(user, telegramChatId, title, username);
        verifyAdminOrWarn(saved);
        channelSyncService.syncChannel(saved);
        postIngestService.ingestFromMessage(saved, forwardedMessage);
        return channelRepository.findById(saved.getId()).orElse(saved);
    }

    @Transactional
    public void ingestPost(ChannelEntity channel, Message message) {
        postIngestService.ingestFromMessage(channel, message);
    }

    @Transactional
    public ChannelEntity connectFromUsername(UserEntity user, String rawInput) {
        String username = normalizeUsername(rawInput);
        if (!CHANNEL_USERNAME.matcher(username).matches()) {
            throw new ChannelConnectException("Некорректный username канала. Пример: @my_channel");
        }

        Chat chat = botApi.getChatByUsername(username)
                .orElseThrow(() -> new ChannelConnectException(
                        "Канал @" + username + " не найден. Проверьте username и что канал публичный."));

        TelegramBotApiService.BotAdminStatus adminStatus = botApi.verifyBotIsAdmin(chat.getId());
        if (!adminStatus.isAdmin()) {
            throw new ChannelConnectException(
                    "Добавьте бота администратором канала @" + username + ".\n"
                            + "Нужны права: публикация сообщений.\n\n"
                            + (adminStatus.errorMessage() != null ? adminStatus.errorMessage() : ""));
        }

        ChannelEntity saved = saveChannel(user, chat.getId(), chat.getTitle(), chat.getUserName());
        saved.setBotIsAdmin(true);
        saved.setCanPostMessages(adminStatus.canPost());
        saved.setCanViewStats(adminStatus.canViewStats());
        channelRepository.save(saved);

        channelSyncService.syncChannel(saved);
        return channelRepository.findById(saved.getId()).orElse(saved);
    }

    /**
     * Подключение ЛЮБОГО публичного канала по ссылке/@username без прав администратора.
     * Данные берутся скрапингом t.me/s/. Бот админом быть не обязан.
     */
    @Transactional
    public ChannelEntity connectPublicChannel(UserEntity user, String rawInput) {
        String username = normalizeUsername(rawInput);
        if (!CHANNEL_USERNAME.matcher(username).matches()) {
            throw new ChannelConnectException(
                    "Некорректная ссылка или username. Пример: @durov или https://t.me/durov");
        }

        Long chatId = null;
        String title = null;
        String resolvedUsername = username;
        TelegramBotApiService.BotAdminStatus adminStatus = null;
        Chat chat = botApi.getChatByUsername(username).orElse(null);
        if (chat != null) {
            chatId = chat.getId();
            title = chat.getTitle();
            if (chat.getUserName() != null) {
                resolvedUsername = chat.getUserName();
            }
            adminStatus = botApi.verifyBotIsAdmin(chat.getId());
        }
        if (chatId == null) {
            // канал не резолвится через Bot API — работаем только по скрапингу
            chatId = syntheticChatId(username);
        }

        ChannelEntity saved = saveChannel(user, chatId, title != null ? title : "@" + username, resolvedUsername);
        if (adminStatus != null) {
            saved.setBotIsAdmin(adminStatus.isAdmin());
            saved.setCanPostMessages(adminStatus.canPost());
            saved.setCanViewStats(adminStatus.canViewStats());
        } else {
            saved.setBotIsAdmin(false);
            saved.setCanPostMessages(false);
            saved.setCanViewStats(false);
        }
        channelRepository.save(saved);

        channelSyncService.syncChannel(saved);
        return channelRepository.findById(saved.getId()).orElse(saved);
    }

    /**
     * Скрейпит публичный канал для сравнения (перк «Анализ конкурента»),
     * НЕ меняя активный канал пользователя. Возвращает персистентную запись с загруженными постами.
     */
    @Transactional
    public ChannelEntity resolveForComparison(String rawInput) {
        String username = normalizeUsername(rawInput);
        if (!CHANNEL_USERNAME.matcher(username).matches()) {
            throw new ChannelConnectException(
                    "Некорректная ссылка. Пример: @durov или https://t.me/durov");
        }

        Long chatId = null;
        String title = null;
        String resolvedUsername = username;
        Chat chat = botApi.getChatByUsername(username).orElse(null);
        if (chat != null) {
            chatId = chat.getId();
            title = chat.getTitle();
            if (chat.getUserName() != null) {
                resolvedUsername = chat.getUserName();
            }
        }
        if (chatId == null) {
            chatId = syntheticChatId(username);
        }

        ChannelEntity channel = channelRepository.findByTelegramChatId(chatId)
                .orElseGet(ChannelEntity::new);
        channel.setTelegramChatId(chatId);
        channel.setTitle(title != null ? title : "@" + username);
        channel.setUsername(resolvedUsername);
        if (channel.getOwnerUserId() == null) {
            channel.setOwnerUserId(0L);
        }
        channel.setConnectionStatus(ConnectionStatus.ACTIVE);
        if (channel.getSubscriberCount() == null) {
            channel.setSubscriberCount(0);
        }
        ChannelEntity saved = channelRepository.save(channel);

        channelSyncService.syncForAnalysis(saved);
        ChannelEntity refreshed = channelRepository.findById(saved.getId()).orElse(saved);
        if (postIngestService.countPosts(refreshed.getId()) == 0) {
            throw new ChannelConnectException(
                    "Не удалось получить посты канала @" + username + ".\n"
                            + "Проверьте, что канал публичный (открывается по t.me/" + username + ").");
        }
        return refreshed;
    }

    private static long syntheticChatId(String username) {
        return -(1_000_000_000L + Math.abs((long) username.toLowerCase().hashCode()));
    }

    private void verifyAdminOrWarn(ChannelEntity channel) {
        if (channel.getTelegramChatId() == null) {
            return;
        }
        TelegramBotApiService.BotAdminStatus status = botApi.verifyBotIsAdmin(channel.getTelegramChatId());
        channel.setBotIsAdmin(status.isAdmin());
        channel.setCanPostMessages(status.canPost());
        channel.setCanViewStats(status.canViewStats());
        channelRepository.save(channel);
    }

    private ChannelEntity saveChannel(UserEntity user, Long telegramChatId, String title, String username) {
        ChannelEntity channel = channelRepository.findByTelegramChatId(telegramChatId)
                .orElseGet(ChannelEntity::new);

        channel.setTelegramChatId(telegramChatId);
        channel.setTitle(title != null ? title : "Канал");
        channel.setUsername(username);
        channel.setOwnerUserId(user.getId());
        channel.setConnectionStatus(ConnectionStatus.ACTIVE);
        if (channel.getSubscriberCount() == null) {
            channel.setSubscriberCount(0);
        }

        ChannelEntity saved = channelRepository.save(channel);

        user.setActiveChannelId(saved.getId());
        userRepository.save(user);

        log.info("Channel connected: userId={}, channelId={}, title={}", user.getId(), saved.getId(), saved.getTitle());
        return saved;
    }

    private static String normalizeUsername(String rawInput) {
        String trimmed = rawInput.trim();
        if (trimmed.startsWith("https://t.me/")) {
            trimmed = trimmed.substring("https://t.me/".length());
        }
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        int slash = trimmed.indexOf('/');
        if (slash > 0) {
            trimmed = trimmed.substring(0, slash);
        }
        return trimmed;
    }
}
