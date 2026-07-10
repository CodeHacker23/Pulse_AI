package org.example.pulse_ai.text;

import org.example.pulse_ai.stats.external.ExternalChannelMetrics;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.stats.model.PublishSlotMetric;
import org.example.pulse_ai.stats.model.TopicMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class StatsMessageBuilder {

    public String build(long requestId, String channelTitle, int subscribers,
                        AnalysisMetrics metrics, ExternalChannelMetrics external) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Статистика #").append(requestId).append("</b>\n");
        sb.append("📢 ").append(TgHtml.b(channelTitle)).append("\n\n");

        sb.append("<b>Сводка за период</b>\n");
        if (subscribers > 0) {
            sb.append("• Подписчиков: <b>").append(formatCompact(subscribers)).append("</b>\n");
        }
        sb.append("• Постов: <b>").append(metrics.postCount()).append("</b>\n");
        sb.append("• Средние просмотры: <b>").append(metrics.avgViews()).append("</b>");
        sb.append(" (").append(formatDelta(metrics.viewsDeltaPercent())).append(")\n");

        Integer extReach = external != null ? external.avgReach() : null;
        if (extReach != null && extReach > 0) {
            sb.append("• Средний охват поста: <b>").append(formatCompact(extReach)).append("</b>\n");
            if (subscribers > 0) {
                double reach = extReach * 100.0 / subscribers;
                sb.append("• Охват: <b>").append(formatOneDecimal(reach)).append("%</b> ")
                        .append("<i>").append(reachLabel(reach)).append("</i>\n");
            }
        } else if (subscribers > 0) {
            double reach = metrics.avgViews() * 100.0 / subscribers;
            sb.append("• Охват: <b>").append(formatOneDecimal(reach)).append("%</b> ")
                    .append("<i>").append(reachLabel(reach)).append("</i>\n");
        }

        Double extErr = external != null ? external.err() : null;
        if (extErr != null && extErr > 0) {
            sb.append("• Вовлечённость (ERR): <b>").append(formatOneDecimal(extErr)).append("%</b>\n");
        }

        Double extCi = external != null ? external.citationIndex() : null;
        if (extCi != null && extCi > 0) {
            sb.append("• Индекс цитирования: <b>").append(formatOneDecimal(extCi)).append("</b>\n");
        }
        sb.append("• Лучшее время: <b>").append(TgHtml.esc(metrics.bestTimeSummary())).append("</b>\n");
        sb.append("• Частота: ").append(TgHtml.esc(metrics.frequencyRecommendation())).append("\n\n");

        if (!metrics.topPosts().isEmpty()) {
            sb.append("🔥 <b>Топ постов</b>\n");
            int i = 1;
            for (PostMetric post : metrics.topPosts()) {
                sb.append("<b>").append(i++).append(".</b> <i>")
                        .append(TgHtml.esc(cleanTitle(post.title()))).append("</i>");
                sb.append(" — <b>").append(post.views()).append("</b> 👁\n");
            }
            sb.append('\n');
        }

        if (!metrics.bestSlots().isEmpty()) {
            sb.append("⏰ <b>Лучшее время</b>\n");
            for (PublishSlotMetric slot : metrics.bestSlots()) {
                sb.append("• ").append(TgHtml.esc(slot.day())).append(' ').append(TgHtml.esc(slot.time()))
                        .append(" — <b>").append(slot.avgViews()).append("</b> 👁\n");
            }
            sb.append('\n');
        }

        if (!metrics.workingTopics().isEmpty()) {
            sb.append("🏷 <b>Темы</b>\n");
            for (TopicMetric topic : metrics.workingTopics()) {
                sb.append("• ").append(TgHtml.esc(topic.topic()))
                        .append(" <i>(").append(topic.postCount()).append(" · ~")
                        .append(topic.avgViews()).append(" 👁)</i>\n");
            }
        }

        if (metrics.limitedAnalysis()) {
            sb.append("\n⚠️ <i>Мало постов — данные ориентировочные.</i>");
        }

        return sb.toString().trim();
    }

    private static String reachLabel(double reach) {
        if (reach >= 40) {
            return "отличный охват";
        }
        if (reach >= 20) {
            return "здоровый охват";
        }
        if (reach >= 10) {
            return "средний охват";
        }
        return "низкий охват";
    }

    private static String formatOneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatCompact(int value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(java.util.Locale.US, "%.1fk", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    private static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = TextHumanizer.humanize(title.replaceAll("\\s+", " ").trim());
        return cleaned.length() > 80 ? cleaned.substring(0, 79) + "..." : cleaned;
    }

    private static String formatDelta(BigDecimal delta) {
        if (delta == null) {
            return "0%";
        }
        return (delta.signum() >= 0 ? "+" : "") + delta + "%";
    }
}
