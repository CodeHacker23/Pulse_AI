package org.example.pulse_ai.text;

import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Конвертация Telegram text entities (жирный/курсив из клиента) в наш markdown (** / _).
 * Публикация и превью уже умеют {@link TgHtml#fromMarkdown}.
 */
public final class TgMarkdown {

    private TgMarkdown() {
    }

    /** Текст сообщения с учётом форматирования Telegram → markdown. */
    public static String fromMessage(Message message) {
        if (message == null) {
            return "";
        }
        String text = message.getText();
        if (text == null) {
            return "";
        }
        return fromEntities(text, message.getEntities());
    }

    public static String fromEntities(String text, List<MessageEntity> entities) {
        if (text == null) {
            return "";
        }
        if (entities == null || entities.isEmpty()) {
            return text;
        }
        List<MessageEntity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator
                .comparingInt(MessageEntity::getOffset)
                .thenComparing(MessageEntity::getLength, Comparator.reverseOrder()));

        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (MessageEntity entity : sorted) {
            int start = Math.max(0, Math.min(text.length(), entity.getOffset()));
            int end = Math.max(start, Math.min(text.length(), entity.getOffset() + entity.getLength()));
            if (start < cursor) {
                // Перекрытие — пропускаем, уже обработали кусок.
                continue;
            }
            out.append(text, cursor, start);
            String chunk = text.substring(start, end);
            out.append(wrap(entity.getType(), chunk));
            cursor = end;
        }
        out.append(text.substring(cursor));
        return out.toString();
    }

    private static String wrap(String type, String chunk) {
        if (type == null || chunk.isEmpty()) {
            return chunk;
        }
        return switch (type) {
            case "bold" -> "**" + chunk + "**";
            case "italic" -> "_" + chunk + "_";
            case "code" -> "`" + chunk + "`";
            case "pre" -> "```\n" + chunk + "\n```";
            default -> chunk;
        };
    }
}
