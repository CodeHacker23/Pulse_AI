package org.example.pulse_ai.text;

/**
 * Маркетинговые тексты воронки: без фейковых цифр, с фокусом на ценность и незавершённость.
 */
public final class ConversionCopy {

    private ConversionCopy() {
    }

    public static String resultHub(String channelTitle, boolean freeTier, int ideaCount) {
        if (freeTier) {
            return """
                    🎯 <b>Отчёт готов — %s</b>

                    Цифры и разбор — у вас. Дальше самое ценное: <b>что публиковать</b> и <b>как не слить охват</b>.

                    • 🧠 все <b>5 секций</b> разбора
                    • 💡 <b>%d идеи</b> под ваш стиль
                    • ✍️ <b>3 черновика</b> — проверить голос бота

                    <i>Один черновик — и вы поймёте, попадает ли бот в вашу аудиторию.</i>
                    """.formatted(TgHtml.b(channelTitle), ideaCount).trim();
        }
        return """
                ✅ <b>Запрос готов — %s</b>

                Всё собрано в одном месте. Выберите раздел:

                • 🧠 Разбор канала (5 секций)
                • 💡 <b>%d идей</b> контента
                • 📈 Дополнительные графики

                <i>Начните с идей — там же можно сгенерировать черновики постов.</i>
                """.formatted(TgHtml.b(channelTitle), ideaCount).trim();
    }

    public static String ideasIntro(String channelTitle, boolean freeTier, int draftsLeft) {
        StringBuilder sb = new StringBuilder();
        sb.append("💡 <b>Что публиковать — «").append(TgHtml.esc(channelTitle)).append("»</b>\n\n");
        sb.append("Жмите <b>Пост N</b> — сразу готовый текст. Потом «🚀 В эфир».\n");
        sb.append("<i>✓ уже брали · ✅ уже в канале</i>\n");
        if (freeTier) {
            sb.append("\n✍️ Бесплатных генераций: <b>").append(draftsLeft).append(" из 3</b>");
        }
        return sb.toString();
    }

    public static String ideaBlock(int number, String title, String reason, String format, String day) {
        return ideaBlock(number, title, reason, format, day, null, null, null);
    }

    public static String ideaBlock(
            int number,
            String title,
            String reason,
            String format,
            String day,
            String closesGap,
            String cta
    ) {
        return ideaBlock(number, title, reason, format, day, closesGap, cta, null);
    }

    public static String ideaBlock(
            int number,
            String title,
            String reason,
            String format,
            String day,
            String closesGap,
            String cta,
            String usedStatus
    ) {
        StringBuilder sb = new StringBuilder();
        String usedMark = usedIdeaPrefix(usedStatus);
        sb.append(TgHtml.b(usedMark + number + ". " + TextHumanizer.humanize(title))).append("\n\n");
        if (usedStatus != null && !usedStatus.isBlank()) {
            sb.append("<i>").append(usedIdeaHint(usedStatus)).append("</i>\n\n");
        }
        if (reason != null && !reason.isBlank()) {
            sb.append(formatIdeaReason(reason)).append("\n\n");
        }
        if (closesGap != null && !closesGap.isBlank() && !"н/д".equalsIgnoreCase(closesGap.trim())) {
            sb.append("📉 Закрывает: <i>").append(TgHtml.esc(closesGap.trim())).append("</i>\n");
        }
        if (day != null || format != null) {
            sb.append("📅 ");
            if (day != null) {
                sb.append(TgHtml.esc(day));
            }
            if (format != null) {
                if (day != null) {
                    sb.append(" · ");
                }
                sb.append(TgHtml.esc(format));
            }
            sb.append('\n');
        }
        if (cta != null && !cta.isBlank()) {
            sb.append("👉 ").append(TgHtml.esc(cta.trim())).append('\n');
        }
        return sb.toString();
    }

    /** Префикс в заголовке идеи: пусто / ✓ / ✅ */
    public static String usedIdeaPrefix(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return switch (status) {
            case "PUBLISHED" -> "✅ ";
            case "DRAFTED", "CHOSEN" -> "✓ ";
            default -> "";
        };
    }

    public static String usedIdeaHint(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "PUBLISHED" -> "уже опубликовано";
            case "DRAFTED" -> "уже есть черновик";
            case "CHOSEN" -> "уже выбирали";
            default -> "уже в работе";
        };
    }

    private static String formatIdeaReason(String reason) {
        String cleaned = TextHumanizer.humanize(reason.replace('\n', ' ').trim());
        if (cleaned.isBlank()) {
            return "";
        }
        String[] sentences = cleaned.split("(?<=[.!?])\\s+");
        if (sentences.length <= 1) {
            return TgHtml.fromMarkdown(cleaned);
        }
        return TgHtml.fromMarkdown(String.join("\n\n", sentences));
    }

    public static String draftHeader(String ideaTitle) {
        return "✍️ <b>Черновик</b> · <i>" + TgHtml.esc(ideaTitle) + "</i>";
    }

    public static String draftNextStepHint() {
        return "\n\n<i>Дальше: «🚀 В эфир» — сейчас или по слоту.</i>";
    }

    public static String draftPaywall() {
        return """
                🔒 <b>Бесплатные черновики закончились</b>

                Вы уже попробовали 3 генерации — это как раз то, что даёт полный запрос, только в масштабе:

                • 12 идей на 2 недели вперёд
                • 7 готовых постов с вариантами
                • больше черновиков на запрос

                <i>Следующий пост можно не писать с нуля.</i>""";
    }

    public static String chartsIntro() {
        return """
                📈 <b>Дополнительная аналитика</b>

                Тепловая карта активности, топ постов и темы — то, что не влезло в первый альбом.

                <i>Сравните пики просмотров с днями публикаций — часто находят «дыру» в контент-плане.</i>""";
    }

    public static String ideasGenerating() {
        return "⏳ <b>Генерирую идеи…</b>\n\n<i>~30 секунд — подбираю под ваши топ-посты.</i>";
    }

    public static String draftGenerating() {
        return "✍️ <b>Пишу черновик…</b>\n\n<i>Подстраиваюсь под стиль вашего канала.</i>";
    }

    public static String publishPreview(String channelTitle, String postText) {
        return """
                📤 <b>«%s»</b>

                %s

                <i>Сейчас или по расписанию?</i>""".formatted(
                TgHtml.esc(channelTitle),
                TgHtml.fromMarkdown(postText)).trim();
    }

    public static String photoCaptionTooLong(int htmlLength, int overflow) {
        return """
                ⚠️ <b>Пост слишком длинный для фото</b>

                Telegram: подпись к фото — максимум <b>1024</b> символа (HTML).
                Сейчас: <b>%d</b> (перебор ~%d).

                Варианты:
                • <b>Сократить</b> — сожму текст под лимит и подберу фото
                • <b>Без фото</b> — опубликуем полным текстом""".formatted(
                htmlLength, Math.max(1, overflow)).trim();
    }

    public static String draftPhotoHint(boolean fitsCaption) {
        if (fitsCaption) {
            return "\n\n<i>🖼 Длина ок для публикации с фото.</i>";
        }
        return "\n\n⚠️ <i>Текст длиннее лимита подписи к фото (1024). "
                + "С картинкой — только после сокращения, либо публикуйте без фото.</i>";
    }

    public static String publishEditPrompt(String currentText) {
        return """
                ✏️ <b>Редактирование поста</b>

                Как будет выглядеть:
                %s

                Пришлите <b>новый текст одним сообщением</b>.
                Можно выделить жирным/курсивом прямо в Telegram — пойму.
                Либо пишите с <code>**жирный**</code> и <code>_курсив_</code>.

                Или нажмите <b>✂️ Короче</b> — ужму текст в стиле автора, финал как был.

                <i>/cancel — отмена</i>""".formatted(
                TgHtml.fromMarkdown(currentText)).trim();
    }

    public static String publishInProgress(String channelTitle) {
        return "⏳ Публикую в «" + TgHtml.esc(channelTitle) + "»…";
    }

    public static String publishSuccess(String channelTitle, String link) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ <b>Пост опубликован!</b>\n\n");
        sb.append("📢 ").append(TgHtml.esc(channelTitle));
        if (link != null && !link.isBlank()) {
            sb.append("\n🔗 <a href=\"").append(TgHtml.esc(link)).append("\">Открыть пост</a>");
        }
        sb.append("\n\n<i>Хотите сгенерировать ещё один пост из этого отчёта?</i>");
        return sb.toString();
    }

    public static String publishFailed(String reason) {
        return """
                ❌ <b>Не удалось опубликовать</b>

                %s

                Текст поста сохранён — попробуйте снова или отредактируйте.""".formatted(
                TgHtml.esc(reason != null ? reason : "Проверьте права бота в канале.")).trim();
    }

    public static String publishBlocked(String reason) {
        return """
                📤 <b>Публикация недоступна</b>

                %s

                <b>Как включить публикацию:</b>
                1. Свой канал → Администраторы → добавьте бота
                2. Право: <b>Публикация сообщений</b>
                3. Перешлите сюда любой пост из <b>своего</b> канала

                Анализ по ссылке ≠ право публиковать в этот канал.""".formatted(reason).trim();
    }

    public static String lockAlert() {
        return "🔒 Этот раздел временно недоступен. Попробуйте позже.";
    }
}
