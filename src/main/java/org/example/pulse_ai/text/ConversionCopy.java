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
        sb.append("<i>Три сильнейшие темы по вашим топ-постам. Выберите одну — сгенерирую готовый текст.</i>\n\n");
        if (freeTier) {
            sb.append("✍️ Бесплатных генераций: <b>").append(draftsLeft).append(" из 3</b>");
        }
        return sb.toString();
    }

    public static String ideaBlock(int number, String title, String reason, String format, String day) {
        StringBuilder sb = new StringBuilder();
        sb.append(TgHtml.b(number + ". " + TextHumanizer.humanize(title))).append("\n\n");
        if (reason != null && !reason.isBlank()) {
            sb.append(formatIdeaReason(reason)).append("\n\n");
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
        return sb.toString();
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
        return "✍️ <b>Черновик поста</b>\n<i>Идея: «" + TgHtml.esc(ideaTitle) + "»</i>";
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
                📤 <b>Публикация в «%s»</b>

                Текст поста:

                %s

                Опубликовать как есть?""".formatted(
                TgHtml.esc(channelTitle),
                TgHtml.fromMarkdown(postText)).trim();
    }

    public static String publishEditPrompt(String currentText) {
        return """
                ✏️ <b>Редактирование поста</b>

                Текущий текст:
                %s

                <i>Отправьте новый текст одним сообщением.</i>""".formatted(
                TgHtml.esc(currentText)).trim();
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

                %s""".formatted(TgHtml.esc(reason)).trim();
    }

    public static String lockAlert() {
        return "🔒 Этот раздел временно недоступен. Попробуйте позже.";
    }
}
