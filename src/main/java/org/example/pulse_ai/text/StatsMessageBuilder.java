package org.example.pulse_ai.text;

import org.example.pulse_ai.stats.external.ExternalChannelMetrics;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.stats.model.TopicMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class StatsMessageBuilder {

    private static final int TOP_POSTS_MAX = 3;
    private static final int TOPICS_MAX = 3;

    public String build(long requestId, String channelTitle, int subscribers,
                        AnalysisMetrics metrics, ExternalChannelMetrics external) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Кратко #").append(requestId).append("</b> · ")
                .append(TgHtml.b(channelTitle)).append("\n\n");

        appendSummary(sb, subscribers, metrics, external);
        appendTopPosts(sb, metrics.topPosts());
        appendTopics(sb, metrics.workingTopics());

        if (metrics.limitedAnalysis()) {
            sb.append("\n⚠️ <i>Мало постов — цифры ориентировочные.</i>\n");
        }
        sb.append("\n📱 <i>Полная статистика канала — в Telegram: ")
                .append("Канал → Статистика (нужны права админа с просмотром статистики).</i>");

        return sb.toString().trim();
    }

    private static void appendSummary(StringBuilder sb, int subscribers,
                                      AnalysisMetrics metrics, ExternalChannelMetrics external) {
        if (subscribers > 0) {
            sb.append("👥 ").append(formatCompact(subscribers));
        }
        sb.append(" · 📝 ").append(metrics.postCount()).append(" постов");
        if (metrics.avgViews() > 0) {
            sb.append(" · 👁 ~").append(metrics.avgViews())
                    .append(" (").append(formatDelta(metrics.viewsDeltaPercent())).append(')');
        } else {
            sb.append(" · 👁 <b>н/д</b>");
        }
        sb.append("\n⏰ ").append(TgHtml.esc(metrics.bestTimeSummary()));
        sb.append(" · ").append(TgHtml.esc(metrics.frequencyRecommendation())).append('\n');

        Integer extReach = external != null ? external.avgReach() : null;
        if (extReach != null && extReach > 0 && subscribers > 0) {
            double reach = extReach * 100.0 / subscribers;
            sb.append("📈 Охват ~").append(formatOneDecimal(reach)).append("%");
            Double extErr = external != null ? external.err() : null;
            if (extErr != null && extErr > 0) {
                sb.append(" · ERR ").append(formatOneDecimal(extErr)).append('%');
            }
            sb.append('\n');
        } else if (subscribers > 0 && metrics.avgViews() > 0) {
            double reach = metrics.avgViews() * 100.0 / subscribers;
            sb.append("📈 Охват ~").append(formatOneDecimal(reach)).append("% ")
                    .append("<i>").append(reachLabel(reach)).append("</i>\n");
        }
        sb.append('\n');
    }

    private static void appendTopPosts(StringBuilder sb, List<PostMetric> topPosts) {
        if (topPosts.isEmpty()) {
            return;
        }
        sb.append("🔥 <b>Топ</b>\n");
        int i = 1;
        for (PostMetric post : topPosts.stream().limit(TOP_POSTS_MAX).toList()) {
            sb.append(i++).append(". <i>").append(TgHtml.esc(cleanTitle(post.title()))).append("</i>");
            if (post.views() > 0) {
                sb.append(" — ").append(post.views()).append(" 👁");
            } else {
                sb.append(" — н/д 👁");
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private static void appendTopics(StringBuilder sb, List<TopicMetric> topics) {
        if (topics.isEmpty()) {
            return;
        }
        sb.append("🏷 ");
        boolean first = true;
        for (TopicMetric topic : topics.stream().limit(TOPICS_MAX).toList()) {
            if (!first) {
                sb.append(" · ");
            }
            first = false;
            sb.append(TgHtml.esc(topic.topic()));
            if (topic.avgViews() > 0) {
                sb.append(" (~").append(topic.avgViews()).append(" 👁)");
            }
        }
        sb.append('\n');
    }

    private static String reachLabel(double reach) {
        if (reach >= 40) {
            return "отлично";
        }
        if (reach >= 20) {
            return "норм";
        }
        if (reach >= 10) {
            return "средне";
        }
        return "низко";
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
        return cleaned.length() > 60 ? cleaned.substring(0, 59) + "…" : cleaned;
    }

    private static String formatDelta(BigDecimal delta) {
        if (delta == null) {
            return "0%";
        }
        return (delta.signum() >= 0 ? "+" : "") + delta + "%";
    }
}
