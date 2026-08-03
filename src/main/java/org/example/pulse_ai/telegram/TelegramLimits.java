package org.example.pulse_ai.telegram;

import org.example.pulse_ai.text.TgHtml;

/**
 * Лимиты Telegram Bot API, которые влияют на UX публикации.
 */
public final class TelegramLimits {

    /** Подпись к фото/медиа (sendPhoto caption). */
    public static final int PHOTO_CAPTION_HTML = 1024;

    /**
     * Целевая длина markdown-черновика, чтобы после конвертации в HTML
     * почти наверняка влезть в caption (запас на теги).
     */
    public static final int PHOTO_SAFE_MARKDOWN = 920;

    private TelegramLimits() {
    }

    public static int captionHtmlLength(String markdownOrPlain) {
        if (markdownOrPlain == null || markdownOrPlain.isBlank()) {
            return 0;
        }
        return TgHtml.fromMarkdown(markdownOrPlain).length();
    }

    public static boolean fitsPhotoCaption(String markdownOrPlain) {
        return captionHtmlLength(markdownOrPlain) <= PHOTO_CAPTION_HTML;
    }

    public static int overflowChars(String markdownOrPlain) {
        return Math.max(0, captionHtmlLength(markdownOrPlain) - PHOTO_CAPTION_HTML);
    }
}
