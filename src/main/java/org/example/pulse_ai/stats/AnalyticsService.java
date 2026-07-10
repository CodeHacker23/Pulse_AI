package org.example.pulse_ai.stats;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.example.pulse_ai.persistence.repository.ChannelPostRepository;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.DailyViewsPoint;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.stats.model.PublishSlotMetric;
import org.example.pulse_ai.stats.model.TopicMetric;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Locale RU = Locale.forLanguageTag("ru");

    private final ChannelPostRepository channelPostRepository;
    private final PulseAnalysisProperties analysisProperties;

    public List<ChannelPostEntity> loadPosts(Long channelId, LocalDate periodFrom, LocalDate periodTo) {
        Instant from = periodFrom.atStartOfDay(MOSCOW).toInstant();
        Instant to = periodTo.plusDays(1).atStartOfDay(MOSCOW).toInstant();
        return channelPostRepository.findByChannelIdAndPublishedAtBetweenOrderByPublishedAtAsc(channelId, from, to);
    }

    public AnalysisMetrics analyze(Long channelId, LocalDate periodFrom, LocalDate periodTo) {
        List<ChannelPostEntity> posts = loadPosts(channelId, periodFrom, periodTo);

        if (posts.size() < analysisProperties.getMinPostsForAnalysis()) {
            List<ChannelPostEntity> allPosts = channelPostRepository.findByChannelIdOrderByPublishedAtAsc(channelId);
            if (allPosts.size() > posts.size()) {
                LocalDate widenedFrom = allPosts.get(0).getPublishedAt().atZone(MOSCOW).toLocalDate();
                LocalDate widenedTo = allPosts.get(allPosts.size() - 1).getPublishedAt().atZone(MOSCOW).toLocalDate();
                return analyzeFromPosts(channelId, allPosts, widenedFrom, widenedTo);
            }
        }
        return analyzeFromPosts(channelId, posts, periodFrom, periodTo);
    }

    public AnalysisMetrics analyzeFromPosts(
            Long channelId,
            List<ChannelPostEntity> posts,
            LocalDate periodFrom,
            LocalDate periodTo
    ) {
        boolean limited = posts.size() < analysisProperties.getMinPostsFull();

        if (posts.isEmpty()) {
            return emptyMetrics(limited);
        }

        int avgViews = (int) posts.stream().mapToInt(p -> safeViews(p)).average().orElse(0);
        BigDecimal avgEr = posts.stream()
                .map(p -> p.getEngagementRate() != null ? p.getEngagementRate() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(posts.size()), 2, RoundingMode.HALF_UP);

        BigDecimal viewsDelta = channelId != null
                ? calculateViewsDelta(channelId, periodFrom, periodTo, avgViews)
                : BigDecimal.ZERO;

        List<PostMetric> ranked = posts.stream()
                .map(this::toPostMetric)
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .toList();

        int topN = Math.min(5, ranked.size());
        int worstN = Math.min(3, ranked.size());

        List<PostMetric> topPosts = ranked.subList(0, topN);
        List<PostMetric> worstPosts = new ArrayList<>(ranked);
        java.util.Collections.reverse(worstPosts);
        worstPosts = worstPosts.stream()
                .limit(worstN)
                .map(p -> withFailureReason(p, avgEr))
                .toList();

        List<DailyViewsPoint> dailyViews = buildDailyViews(posts, periodFrom, periodTo);
        List<PublishSlotMetric> bestSlots = buildBestSlots(posts);
        List<PublishSlotMetric> avoidSlots = buildAvoidSlots(posts, avgEr);
        List<TopicMetric> topics = extractTopics(posts);

        String bestTime = bestSlots.isEmpty()
                ? "—"
                : bestSlots.get(0).day() + " " + bestSlots.get(0).time();

        return new AnalysisMetrics(
                posts.size(),
                avgViews,
                avgEr,
                viewsDelta,
                dailyViews,
                topPosts,
                worstPosts,
                bestSlots,
                avoidSlots,
                topics,
                bestTime,
                recommendFrequency(posts.size()),
                limited
        );
    }

    private AnalysisMetrics emptyMetrics(boolean limited) {
        return new AnalysisMetrics(
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "—", "Публикуйте 3–4 раза в неделю", limited
        );
    }

    private BigDecimal calculateViewsDelta(Long channelId, LocalDate periodFrom, LocalDate periodTo, int currentAvg) {
        long days = ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        LocalDate prevTo = periodFrom.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        List<ChannelPostEntity> prev = loadPosts(channelId, prevFrom, prevTo);
        if (prev.isEmpty() || currentAvg == 0) {
            return BigDecimal.ZERO;
        }
        int prevAvg = (int) prev.stream().mapToInt(this::safeViews).average().orElse(0);
        if (prevAvg == 0) {
            return BigDecimal.ZERO;
        }
        double delta = (currentAvg - prevAvg) * 100.0 / prevAvg;
        return BigDecimal.valueOf(delta).setScale(1, RoundingMode.HALF_UP);
    }

    private List<DailyViewsPoint> buildDailyViews(List<ChannelPostEntity> posts, LocalDate from, LocalDate to) {
        Map<LocalDate, int[]> buckets = new HashMap<>();
        for (ChannelPostEntity post : posts) {
            LocalDate day = post.getPublishedAt().atZone(MOSCOW).toLocalDate();
            int[] bucket = buckets.computeIfAbsent(day, d -> new int[2]);
            bucket[0] += safeViews(post);
            bucket[1] += 1;
        }

        List<DailyViewsPoint> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int[] bucket = buckets.getOrDefault(day, new int[]{0, 0});
            points.add(new DailyViewsPoint(day, bucket[0], bucket[1]));
        }
        return points;
    }

    private List<PublishSlotMetric> buildBestSlots(List<ChannelPostEntity> posts) {
        Map<String, List<BigDecimal>> slotErs = new HashMap<>();
        Map<String, List<Integer>> slotViews = new HashMap<>();
        for (ChannelPostEntity post : posts) {
            var zdt = post.getPublishedAt().atZone(MOSCOW);
            String day = zdt.getDayOfWeek().getDisplayName(TextStyle.FULL, RU);
            int hour = zdt.getHour();
            String slot = hour < 12 ? "09:00" : hour < 17 ? "14:00" : "19:00";
            String key = day + "|" + slot;
            slotErs.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(post.getEngagementRate() != null ? post.getEngagementRate() : BigDecimal.ZERO);
            slotViews.computeIfAbsent(key, k -> new ArrayList<>()).add(safeViews(post));
        }

        // Ранжируем по просмотрам (надёжная метрика): реакции недоступны через t.me/s/
        return slotErs.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("\\|");
                    BigDecimal avgEr = average(e.getValue());
                    int avgViews = (int) slotViews.get(e.getKey()).stream()
                            .mapToInt(Integer::intValue).average().orElse(0);
                    return new PublishSlotMetric(parts[0], parts[1], avgEr, avgViews);
                })
                .sorted(Comparator.comparingInt(PublishSlotMetric::avgViews).reversed())
                .limit(3)
                .toList();
    }

    private List<PublishSlotMetric> buildAvoidSlots(List<ChannelPostEntity> posts, BigDecimal avgEr) {
        return buildBestSlots(posts).stream()
                .sorted(Comparator.comparingInt(PublishSlotMetric::avgViews))
                .limit(1)
                .toList();
    }

    private List<TopicMetric> extractTopics(List<ChannelPostEntity> posts) {
        Map<String, List<BigDecimal>> topicErs = new HashMap<>();
        Map<String, List<Integer>> topicViews = new HashMap<>();
        for (ChannelPostEntity post : posts) {
            String topic = guessTopic(post.getFullText());
            topicErs.computeIfAbsent(topic, k -> new ArrayList<>())
                    .add(post.getEngagementRate() != null ? post.getEngagementRate() : BigDecimal.ZERO);
            topicViews.computeIfAbsent(topic, k -> new ArrayList<>()).add(safeViews(post));
        }

        return topicErs.entrySet().stream()
                .map(e -> new TopicMetric(
                        e.getKey(),
                        average(e.getValue()),
                        e.getValue().size(),
                        (int) topicViews.get(e.getKey()).stream().mapToInt(Integer::intValue).average().orElse(0)
                ))
                .sorted(Comparator.comparingInt(TopicMetric::avgViews).reversed())
                .limit(5)
                .toList();
    }

    private static String guessTopic(String text) {
        if (text == null || text.isBlank()) {
            return "Общее";
        }
        String lower = text.toLowerCase();
        if (lower.contains("кейс") || lower.contains("история")) {
            return "Кейсы";
        }
        if (lower.contains("совет") || lower.contains("как ")) {
            return "Советы";
        }
        if (lower.contains("?") || lower.contains("опрос")) {
            return "Вовлечение";
        }
        if (lower.contains("новост") || lower.contains("анонс")) {
            return "Новости";
        }
        return "Экспертный контент";
    }

    private PostMetric toPostMetric(ChannelPostEntity post) {
        String title = post.getTextPreview() != null ? post.getTextPreview() : "Пост";
        if (title.length() > 80) {
            title = title.substring(0, 77) + "…";
        }
        return new PostMetric(
                post.getTelegramMessageId(),
                title,
                safeViews(post),
                post.getReactionsTotal() != null ? post.getReactionsTotal() : 0,
                post.getEngagementRate() != null ? post.getEngagementRate() : BigDecimal.ZERO,
                null
        );
    }

    private PostMetric withFailureReason(PostMetric post, BigDecimal avgEr) {
        String reason = "ER ниже среднего по каналу";
        if (post.engagementRate().compareTo(avgEr.multiply(BigDecimal.valueOf(0.5))) < 0) {
            reason = "Слабый hook в первых строках или неудачное время публикации";
        }
        return new PostMetric(
                post.messageId(), post.title(), post.views(), post.reactions(),
                post.engagementRate(), reason
        );
    }

    private double score(PostMetric post) {
        return post.views() * 0.6 + post.engagementRate().doubleValue() * 1000 * 0.4;
    }

    private int safeViews(ChannelPostEntity post) {
        return post.getViews() != null ? post.getViews() : 0;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private static String recommendFrequency(int postCount) {
        if (postCount >= 20) {
            return "4–5 раз в неделю, чередуя форматы";
        }
        if (postCount >= 10) {
            return "3–4 раза в неделю";
        }
        return "2–3 раза в неделю для стабильного роста";
    }
}
