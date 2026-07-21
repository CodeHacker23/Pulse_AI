package org.example.pulse_ai.domain.publish;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.analysis.GeneratedPostService;
import org.example.pulse_ai.domain.channel.DiscussionLinkService;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.entity.PublishedPostEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.PublishedPostRepository;
import org.example.pulse_ai.telegram.TelegramBotApiService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelPublishService {

    private static final int TELEGRAM_CAPTION_LIMIT = 1024;

    private final TelegramBotApiService botApi;
    private final TelegramMessageSender messageSender;
    private final ChannelRepository channelRepository;
    private final PublishedPostRepository publishedPostRepository;
    private final DiscussionLinkService discussionLinkService;

    public PublishReadiness checkReadiness(ChannelEntity channel) {
        if (channel.getTelegramChatId() == null) {
            return PublishReadiness.blocked(
                    "Канал не подключён к Telegram. Перешлите пост из своего канала, где бот — админ.");
        }
        TelegramBotApiService.BotAdminStatus status = botApi.verifyBotIsAdmin(channel.getTelegramChatId());
        channel.setBotIsAdmin(status.isAdmin());
        channel.setCanPostMessages(status.canPost());
        channel.setCanViewStats(status.canViewStats());
        channelRepository.save(channel);

        if (!status.isAdmin() || !status.canPost()) {
            String hint = status.errorMessage() != null
                    ? status.errorMessage()
                    : "Нужны права администратора с публикацией сообщений.";
            return PublishReadiness.blocked(
                    "Бот не может публиковать в «" + channel.getTitle() + "».\n\n"
                            + hint + "\n\n"
                            + "Перешлите пост из своего канала после добавления бота админом.");
        }
        return PublishReadiness.ok();
    }

    @Transactional
    public PublishResult publish(
            UserEntity user,
            ChannelEntity channel,
            GeneratedPostEntity generatedPost,
            String finalText
    ) {
        return publish(user, channel, generatedPost, finalText, generatedPost.getImageUrl());
    }

    @Transactional
    public PublishResult publish(
            UserEntity user,
            ChannelEntity channel,
            GeneratedPostEntity generatedPost,
            String finalText,
            String imageUrl
    ) {
        PublishReadiness readiness = checkReadiness(channel);
        if (!readiness.allowed()) {
            return PublishResult.failure(readiness.message());
        }

        long chatId = channel.getTelegramChatId();

        if (GeneratedPostService.isPoll(generatedPost)) {
            return publishPoll(user, channel, generatedPost, finalText, chatId);
        }

        // Конвертируем markdown (**жирный**, _курсив_, списки) в Telegram HTML, чтобы разметка отобразилась.
        String html = TgHtml.fromMarkdown(finalText);
        Integer messageId;
        if (imageUrl != null && !imageUrl.isBlank()) {
            if (html.length() <= TELEGRAM_CAPTION_LIMIT) {
                messageId = messageSender.sendPhotoUrlToChannel(chatId, imageUrl, html);
            } else {
                messageSender.sendPhotoUrlToChannel(chatId, imageUrl, null);
                messageId = messageSender.sendToChannel(chatId, html);
            }
        } else {
            messageId = messageSender.sendToChannel(chatId, html);
        }
        if (messageId == null) {
            return PublishResult.failure(
                    "Не удалось отправить пост в канал. Проверьте права бота и попробуйте снова.");
        }

        return savePublished(user, channel, generatedPost, finalText, messageId);
    }

    /**
     * Анонимный опрос → прямо в канал.
     * Неанонимный → в группу обсуждений (там Telegram это разрешает) + анонс в канал со ссылкой.
     */
    private PublishResult publishPoll(
            UserEntity user,
            ChannelEntity channel,
            GeneratedPostEntity generatedPost,
            String finalText,
            long channelChatId
    ) {
        List<String> options = GeneratedPostService.splitOptions(generatedPost.getPollOptions());
        String question = finalText != null && !finalText.isBlank()
                ? finalText
                : generatedPost.getVariantA();
        if (options.size() < 2) {
            return PublishResult.failure(
                    "У опроса меньше 2 вариантов. Откройте идею снова или введите свои варианты.");
        }

        boolean anonymous = generatedPost.isPollAnonymous();
        if (anonymous) {
            var pollResult = messageSender.sendPollToChannelDetailed(channelChatId, question, options, true);
            if (!pollResult.ok()) {
                return PublishResult.failure(pollFailMessage(pollResult.error(), true));
            }
            return savePublished(user, channel, generatedPost, question, pollResult.messageId());
        }

        Long discussionId = discussionLinkService.resolveDiscussionChatId(channel);
        if (discussionId == null) {
            return PublishResult.failure("""
                    Для неанонимного опроса нужна группа обсуждений канала.

                    1. Настройки канала → Обсуждение → включите
                    2. Добавьте бота в эту группу администратором
                    3. Опубликуйте любой пост с комментариями (бот сам подхватит группу) или попробуйте снова

                    Либо переключите опрос на «🕶 Анонимный» — тогда он уйдёт прямо в канал.""");
        }

        var pollResult = messageSender.sendPollToChannelDetailed(discussionId, question, options, false);
        if (!pollResult.ok()) {
            return PublishResult.failure("""
                    Не удалось отправить неанонимный опрос в группу обсуждений.

                    Причина: %s

                    Убедитесь, что бот — админ в группе комментариев канала.
                    Или включите «🕶 Анонимный» и опубликуйте опрос прямо в канал."""
                    .formatted(pollResult.error() != null ? pollResult.error() : "ошибка Telegram"));
        }

        String pollLink = buildDiscussionMessageLink(discussionId, pollResult.messageId());
        String announce = "📊 <b>Опрос</b> · видно, кто голосовал\n\n"
                + "<b>" + TgHtml.esc(question) + "</b>\n\n"
                + (pollLink != null
                ? "👉 <a href=\"" + TgHtml.esc(pollLink) + "\">Голосовать в комментариях</a>"
                : "👉 Откройте комментарии под этим постом и проголосуйте.");

        Integer channelMsgId = messageSender.sendToChannel(channelChatId, announce);
        if (channelMsgId == null) {
            // Опрос уже в комментариях — считаем успехом, отдаём ссылку на него.
            log.warn("Poll posted to discussion {}, but channel announce failed", discussionId);
            return savePublished(user, channel, generatedPost, question, pollResult.messageId(), pollLink);
        }
        String channelLink = buildPostLink(channel, channelMsgId);
        return savePublished(user, channel, generatedPost, question, channelMsgId, channelLink);
    }

    private static String buildDiscussionMessageLink(long discussionChatId, int messageId) {
        String raw = String.valueOf(discussionChatId);
        if (raw.startsWith("-100")) {
            return "https://t.me/c/" + raw.substring(4) + "/" + messageId;
        }
        return null;
    }

    private static String pollFailMessage(String apiErr, boolean anonymous) {
        return "Не удалось отправить опрос.\n\n"
                + "Причина Telegram: " + (apiErr != null ? apiErr : "неизвестно") + "\n\n"
                + (anonymous
                ? "Проверьте, что бот — админ канала с правом публикации."
                : "Для неанонимного нуженна группа обсуждений; либо опубликуйте анонимный опрос в канал.");
    }

    private PublishResult savePublished(
            UserEntity user,
            ChannelEntity channel,
            GeneratedPostEntity generatedPost,
            String finalStored,
            Integer messageId
    ) {
        return savePublished(user, channel, generatedPost, finalStored, messageId, buildPostLink(channel, messageId));
    }

    private PublishResult savePublished(
            UserEntity user,
            ChannelEntity channel,
            GeneratedPostEntity generatedPost,
            String finalStored,
            Integer messageId,
            String link
    ) {
        PublishedPostEntity published = new PublishedPostEntity();
        published.setGeneratedPostId(generatedPost.getId());
        published.setUserId(user.getId());
        published.setChannelId(channel.getId());
        published.setVariantUsed('A');
        published.setFinalText(finalStored);
        published.setTelegramMessageId(messageId);
        published.setPostLink(link);
        publishedPostRepository.save(published);

        log.info("Published {} {} to channel {} (msg {})",
                GeneratedPostService.isPoll(generatedPost) ? "poll" : "post",
                generatedPost.getId(), channel.getId(), messageId);
        return PublishResult.success(messageId, link);
    }

    public static String buildPostLink(ChannelEntity channel, int messageId) {
        if (channel.getUsername() != null && !channel.getUsername().isBlank()) {
            return "https://t.me/" + channel.getUsername() + "/" + messageId;
        }
        long chatId = channel.getTelegramChatId();
        String raw = String.valueOf(chatId);
        if (raw.startsWith("-100")) {
            return "https://t.me/c/" + raw.substring(4) + "/" + messageId;
        }
        return null;
    }

    public record PublishReadiness(boolean allowed, String message) {
        public static PublishReadiness ok() {
            return new PublishReadiness(true, null);
        }

        public static PublishReadiness blocked(String message) {
            return new PublishReadiness(false, message);
        }
    }

    public record PublishResult(boolean success, Integer messageId, String link, String error) {
        public static PublishResult success(int messageId, String link) {
            return new PublishResult(true, messageId, link, null);
        }

        public static PublishResult failure(String error) {
            return new PublishResult(false, null, null, error);
        }
    }
}
