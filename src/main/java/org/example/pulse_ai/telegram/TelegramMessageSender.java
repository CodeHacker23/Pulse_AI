package org.example.pulse_ai.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.ByteArrayInputStream;
import java.util.List;

@Slf4j
@Service
public class TelegramMessageSender {

    private final AbsSender bot;

    public TelegramMessageSender(ObjectProvider<ChannelPulseBot> botProvider) {
        this.bot = botProvider.getIfAvailable();
    }

    public void answerPreCheckout(String preCheckoutQueryId, boolean ok, String errorMessage) {
        if (bot == null) {
            return;
        }
        try {
            AnswerPreCheckoutQuery.AnswerPreCheckoutQueryBuilder builder = AnswerPreCheckoutQuery.builder()
                    .preCheckoutQueryId(preCheckoutQueryId)
                    .ok(ok);
            if (!ok && errorMessage != null) {
                builder.errorMessage(errorMessage);
            }
            bot.execute(builder.build());
        } catch (TelegramApiException e) {
            log.warn("Не удалось ответить на pre_checkout: {}", e.getMessage());
        }
    }

    public boolean sendStarsInvoice(long chatId, String title, String description, String payload, int starsAmount) {
        if (bot == null) {
            log.debug("Bot disabled, invoice to {}", chatId);
            return false;
        }
        try {
            bot.execute(SendInvoice.builder()
                    .chatId(chatId)
                    .title(title)
                    .description(description)
                    .payload(payload)
                    .currency("XTR")
                    .providerToken("")
                    .prices(List.of(LabeledPrice.builder()
                            .label(title)
                            .amount(starsAmount)
                            .build()))
                    .build());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить Stars invoice в {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    public void answerCallback(String callbackQueryId) {
        if (bot == null) {
            log.debug("Skip callback answer: bot disabled");
            return;
        }
        try {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build());
        } catch (TelegramApiException e) {
            log.warn("Не удалось ответить на callback: {}", e.getMessage());
        }
    }

    public void answerCallbackWithAlert(String callbackQueryId, String text) {
        if (bot == null) {
            log.debug("Skip callback alert: bot disabled");
            return;
        }
        try {
            bot.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .showAlert(true)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Не удалось показать alert на callback: {}", e.getMessage());
        }
    }

    public void sendText(long chatId, String text) {
        sendText(chatId, text, null);
    }

    public boolean sendTextSafe(long chatId, String text) {
        return sendTextSafe(chatId, text, null);
    }

    public void sendText(long chatId, String text, ReplyKeyboard keyboard) {
        if (bot == null) {
            log.debug("Bot disabled, message to {}: {}", chatId, text);
            return;
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .build();
        if (keyboard != null) {
            message.setReplyMarkup(keyboard);
        }
        execute(message);
    }

    public boolean sendTextSafe(long chatId, String text, ReplyKeyboard keyboard) {
        try {
            sendText(chatId, text, keyboard);
            return true;
        } catch (Exception ex) {
            log.warn("Не удалось отправить сообщение в {}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    public void sendTextWithInline(long chatId, String text, InlineKeyboardMarkup keyboard) {
        if (bot == null) {
            log.debug("Bot disabled, inline message to {}: {}", chatId, text);
            return;
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .replyMarkup(keyboard)
                .build();
        execute(message);
    }

    public boolean sendTextWithInlineSafe(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            sendTextWithInline(chatId, text, keyboard);
            return true;
        } catch (Exception ex) {
            log.warn("Не удалось отправить inline-сообщение в {}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    public void editText(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (bot == null) {
            log.debug("Bot disabled, edit message to {}: {}", chatId, text);
            return;
        }
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text)
                .parseMode("HTML")
                .replyMarkup(keyboard)
                .build();
        execute(edit);
    }

    /**
     * Редактирует текст сообщения. Если это фото/медиа без текста
     * ({@code there is no text in the message to edit}) — удаляет старое и шлёт новое.
     *
     * @return messageId актуального UI-сообщения (может смениться после replace)
     */
    public int editTextOrReplace(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (bot == null) {
            return messageId;
        }
        if (messageId <= 0) {
            sendTextWithInlineSafe(chatId, text, keyboard);
            return 0;
        }
        try {
            bot.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
            return messageId;
        } catch (TelegramApiException e) {
            String err = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (err.contains("message is not modified")) {
                return messageId;
            }
            if (err.contains("no text in the message")
                    || err.contains("message to edit not found")
                    || err.contains("message can't be edited")) {
                deleteMessageSafe(chatId, messageId);
                Integer newId = sendReturningMessageIdWithInline(chatId, text, keyboard);
                return newId != null ? newId : 0;
            }
            throw new IllegalStateException("Не удалось обновить сообщение в Telegram", e);
        }
    }

    public Integer sendReturningMessageIdWithInline(long chatId, String text, InlineKeyboardMarkup keyboard) {
        if (bot == null) {
            return null;
        }
        try {
            return bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build()).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить сообщение в {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Sends a message and returns its Telegram messageId, or {@code null} if it could not be sent. */
    public Integer sendReturningMessageId(long chatId, String text) {
        if (bot == null) {
            log.debug("Bot disabled, message to {}: {}", chatId, text);
            return null;
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .build();
        try {
            return bot.execute(message).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить сообщение прогресса в {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Edits a message text without a keyboard, swallowing any Telegram errors (best-effort). */
    public void editTextSafe(long chatId, int messageId, String text) {
        if (bot == null) {
            return;
        }
        try {
            bot.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось обновить сообщение прогресса {}: {}", messageId, e.getMessage());
        }
    }

    public void deleteMessageSafe(long chatId, int messageId) {
        if (bot == null) {
            return;
        }
        try {
            bot.execute(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось удалить сообщение {}: {}", messageId, e.getMessage());
        }
    }

    public void sendPhotoBytes(long chatId, byte[] image, String caption) {
        if (bot == null) {
            log.debug("Bot disabled, photo to {}: {}", chatId, caption);
            return;
        }
        SendPhoto photo = SendPhoto.builder()
                .chatId(chatId)
                .photo(new InputFile(new ByteArrayInputStream(image), "chart.png"))
                .caption(caption)
                .parseMode("HTML")
                .build();
        try {
            bot.execute(photo);
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Не удалось отправить график в Telegram", e);
        }
    }

    public boolean sendPhotoSafe(long chatId, byte[] image, String caption) {
        try {
            sendPhotoBytes(chatId, image, caption);
            return true;
        } catch (Exception ex) {
            log.warn("Не удалось отправить график в {}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    public boolean sendPhotoAlbumSafe(
            long chatId,
            byte[] firstImage,
            String firstCaption,
            byte[] secondImage,
            String secondCaption
    ) {
        return sendPhotoAlbumSafe(chatId, List.of(
                new AlbumPhoto(firstImage, firstCaption),
                new AlbumPhoto(secondImage, secondCaption)));
    }

    public boolean sendPhotoAlbumSafe(long chatId, List<AlbumPhoto> photos) {
        if (bot == null) {
            log.debug("Bot disabled, media group to {}", chatId);
            return false;
        }
        List<AlbumPhoto> valid = photos.stream()
                .filter(p -> p.image() != null && p.image().length > 0)
                .toList();
        if (valid.size() < 2) {
            return false;
        }
        try {
            List<InputMedia> medias = new java.util.ArrayList<>();
            for (int i = 0; i < valid.size(); i++) {
                AlbumPhoto p = valid.get(i);
                InputMediaPhoto media = new InputMediaPhoto();
                media.setMedia(new ByteArrayInputStream(p.image()), "chart-" + i + ".png");
                media.setCaption(p.caption());
                media.setParseMode("HTML");
                medias.add(media);
            }
            SendMediaGroup mediaGroup = SendMediaGroup.builder()
                    .chatId(chatId)
                    .medias(medias)
                    .build();
            bot.execute(mediaGroup);
            return true;
        } catch (Exception ex) {
            log.warn("Не удалось отправить альбом графиков в {}: {}", chatId, ex.getMessage());
            return false;
        }
    }

    public record AlbumPhoto(byte[] image, String caption) {
    }

    /** Replaces message media with a photo URL and optional caption/keyboard (best-effort). */
    public boolean editPhotoUrlSafe(long chatId, int messageId, String imageUrl, String caption, InlineKeyboardMarkup keyboard) {
        if (bot == null || messageId <= 0) {
            return false;
        }
        try {
            InputMediaPhoto media = InputMediaPhoto.builder()
                    .media(telegramPhotoMedia(imageUrl))
                    .parseMode("HTML")
                    .build();
            if (caption != null && !caption.isBlank()) {
                media.setCaption(caption);
            }
            EditMessageMedia edit = EditMessageMedia.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .media(media)
                    .replyMarkup(keyboard)
                    .build();
            bot.execute(edit);
            return true;
        } catch (TelegramApiException e) {
            log.warn("Не удалось обновить фото в сообщении {}: {}", messageId, e.getMessage());
            return false;
        }
    }

    /** Sends a photo by URL with an inline keyboard (preview to the user). Best-effort. */
    public boolean sendPhotoUrlWithInlineSafe(long chatId, String imageUrl, String caption, InlineKeyboardMarkup keyboard) {
        if (bot == null) {
            log.debug("Bot disabled, photo-url to {}", chatId);
            return false;
        }
        try {
            SendPhoto.SendPhotoBuilder builder = SendPhoto.builder()
                    .chatId(chatId)
                    .photo(photoInput(imageUrl))
                    .parseMode("HTML");
            if (caption != null && !caption.isBlank()) {
                builder.caption(caption);
            }
            if (keyboard != null) {
                builder.replyMarkup(keyboard);
            }
            bot.execute(builder.build());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить фото по URL в {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Publishes a photo by URL / tgfile:id to a channel with an optional HTML caption.
     * Returns message id or null on failure. Caption must be ≤ 1024 chars (Telegram limit).
     */
    public Integer sendPhotoUrlToChannel(long channelChatId, String imageUrl, String caption) {
        if (bot == null) {
            log.debug("Bot disabled, channel photo to {}", channelChatId);
            return null;
        }
        try {
            SendPhoto.SendPhotoBuilder builder = SendPhoto.builder()
                    .chatId(channelChatId)
                    .photo(photoInput(imageUrl))
                    .parseMode("HTML");
            if (caption != null && !caption.isBlank()) {
                builder.caption(caption);
            }
            return bot.execute(builder.build()).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Не удалось опубликовать фото в канал {}: {}", channelChatId, e.getMessage());
            return null;
        }
    }

    /** Своё фото юзера храним как {@code tgfile:<file_id>}. */
    public static String tgFileRef(String fileId) {
        return fileId == null || fileId.isBlank() ? null : "tgfile:" + fileId.trim();
    }

    public static boolean isTgFileRef(String imageUrl) {
        return imageUrl != null && imageUrl.startsWith("tgfile:");
    }

    private static InputFile photoInput(String imageUrl) {
        if (isTgFileRef(imageUrl)) {
            return new InputFile(imageUrl.substring("tgfile:".length()));
        }
        return new InputFile(imageUrl);
    }

    private static String telegramPhotoMedia(String imageUrl) {
        if (isTgFileRef(imageUrl)) {
            return imageUrl.substring("tgfile:".length());
        }
        return imageUrl;
    }

    /**
     * Sends a native Telegram poll to a chat (channel or discussion group).
     * Для неанонимных опросов в канал используйте группу обсуждений — см. ChannelPublishService.
     */
    public Integer sendPollToChannel(long channelChatId, String question, List<String> options, boolean anonymous) {
        PollSendResult result = sendPollToChannelDetailed(channelChatId, question, options, anonymous);
        return result.messageId();
    }

    public record PollSendResult(Integer messageId, String error) {
        public boolean ok() {
            return messageId != null;
        }
    }

    public PollSendResult sendPollToChannelDetailed(
            long chatId,
            String question,
            List<String> options,
            boolean anonymous
    ) {
        if (bot == null) {
            log.debug("Bot disabled, poll to {}", chatId);
            return new PollSendResult(null, "бот выключен");
        }
        if (question == null || question.isBlank()) {
            return new PollSendResult(null, "пустой вопрос опроса");
        }
        if (options == null || options.size() < 2) {
            return new PollSendResult(null, "нужно 2–10 вариантов ответа");
        }
        List<String> trimmed = options.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(10)
                .toList();
        if (trimmed.size() < 2) {
            return new PollSendResult(null, "нужно 2–10 вариантов ответа");
        }
        String q = question.length() > 300 ? question.substring(0, 297) + "…" : question;
        return trySendPoll(String.valueOf(chatId), q, trimmed, anonymous);
    }

    private PollSendResult trySendPoll(String chatId, String question, List<String> options, boolean anonymous) {
        try {
            Integer messageId = bot.execute(SendPoll.builder()
                    .chatId(chatId)
                    .question(question)
                    .options(options)
                    .isAnonymous(anonymous)
                    .allowMultipleAnswers(false)
                    .type("regular")
                    .build()).getMessageId();
            return new PollSendResult(messageId, null);
        } catch (TelegramApiException e) {
            String details = e.getMessage() != null ? e.getMessage() : e.toString();
            log.warn("Не удалось опубликовать опрос в {}: anonymous={}, err={}", chatId, anonymous, details);
            return new PollSendResult(null, details);
        }
    }

    public Integer sendToChannel(long channelChatId, String text) {
        if (bot == null) {
            log.debug("Bot disabled, channel post to {}", channelChatId);
            return null;
        }
        try {
            return bot.execute(SendMessage.builder()
                    .chatId(channelChatId)
                    .text(text)
                    .parseMode("HTML")
                    .disableWebPagePreview(false)
                    .build()).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Не удалось опубликовать в канал {}: {}", channelChatId, e.getMessage());
            return null;
        }
    }

    /** Sends a reply into a chat (e.g. discussion group comment thread). Returns true on success. */
    public boolean sendReplyToChat(long chatId, String text, int replyToMessageId) {
        if (bot == null) {
            log.debug("Bot disabled, reply to {} in {}", replyToMessageId, chatId);
            return false;
        }
        try {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyToMessageId(replyToMessageId)
                    .disableWebPagePreview(true)
                    .build());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Не удалось ответить в чат {} на сообщение {}: {}", chatId, replyToMessageId, e.getMessage());
            return false;
        }
    }

    /** Edits a channel post (bot must be admin). Returns true on success. */
    public boolean editChannelMessage(long channelChatId, int messageId, String text) {
        if (bot == null) {
            return false;
        }
        try {
            bot.execute(EditMessageText.builder()
                    .chatId(channelChatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .build());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Не удалось отредактировать пост {} в канале {}: {}", messageId, channelChatId, e.getMessage());
            return false;
        }
    }

    public void removeKeyboard(long chatId, String text) {
        sendText(chatId, text, new ReplyKeyboardRemove(true));
    }

    private void execute(SendMessage message) {
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Не удалось отправить сообщение в Telegram", e);
        }
    }

    private void execute(EditMessageText edit) {
        try {
            bot.execute(edit);
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Не удалось обновить сообщение в Telegram", e);
        }
    }
}
