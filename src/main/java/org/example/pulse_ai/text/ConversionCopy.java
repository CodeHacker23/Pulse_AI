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

                    Цифры вы уже видите. Дальше — то, ради чего приходят: <b>что публиковать</b> и <b>как это сформулировать</b>.

                    • 🧠 Разбор канала — открыт 1 из 5 разделов
                    • 💡 <b>%d идеи</b> под ваш стиль — <b>бесплатно</b>
                    • ✍️ <b>3 черновика поста</b> — попробовать генерацию
                    • 📈 Дополнительные графики

                    <i>Один черновик — и вы почувствуете, насколько бот попадает в ваш голос.</i>
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
        sb.append(TgHtml.b(number + ". " + TextHumanizer.humanize(title))).append('\n');
        if (reason != null && !reason.isBlank()) {
            sb.append("<i>").append(TgHtml.esc(reason)).append("</i>\n");
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

    public static String draftHeader(String ideaTitle) {
        return "✍️ <b>Черновик поста</b>\n<i>Идея: «" + TgHtml.esc(ideaTitle) + "»</i>";
    }

    public static String draftPaywall() {
        return """
                🔒 <b>Бесплатные черновики закончились</b>

                Вы уже попробовали 3 генерации — это как раз то, что даёт полный запрос, только в масштабе:

                • 12 идей на 2 недели вперёд
                • 7 готовых постов с вариантами
                • полный разбор всех 5 разделов

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

    public static String lockAlert() {
        return "🔒 Этот раздел — в полном запросе.\n\nБесплатно: «Главное» + «Идеи» с 3 генерациями поста.";
    }
}
