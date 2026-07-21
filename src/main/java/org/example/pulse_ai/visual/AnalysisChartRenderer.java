package org.example.pulse_ai.visual;

import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.DailyViewsPoint;
import org.example.pulse_ai.stats.model.PostMetric;
import org.example.pulse_ai.stats.model.PublishSlotMetric;
import org.example.pulse_ai.stats.model.TopicMetric;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.style.CategoryStyler;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

@Component
public class AnalysisChartRenderer {

    private static final Color BG = new Color(15, 17, 26);
    private static final Color CARD = new Color(24, 28, 42);
    private static final Color ACCENT = new Color(99, 102, 241);
    private static final Color ACCENT_2 = new Color(34, 211, 238);
    private static final Color ACCENT_3 = new Color(167, 139, 250);
    private static final Color TEXT = new Color(241, 245, 249);
    private static final Color MUTED = new Color(148, 163, 184);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    public AnalysisChartPack render(String channelTitle, AnalysisMetrics metrics) {
        return render(channelTitle, metrics, null);
    }

    public AnalysisChartPack render(String channelTitle, AnalysisMetrics metrics, Double errPercent) {
        return render(channelTitle, metrics, errPercent, null);
    }

    public AnalysisChartPack render(String channelTitle, AnalysisMetrics metrics, Double errPercent, Double reachPercent) {
        return new AnalysisChartPack(
                renderDashboard(channelTitle, metrics, errPercent, reachPercent),
                renderViewsTrend(metrics),
                new byte[0],
                new byte[0],
                new byte[0]
        );
    }

    private byte[] renderDashboard(String channelTitle, AnalysisMetrics metrics, Double errPercent, Double reachPercent) {
        int width = 1200;
        int height = 720;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.drawString("ChannelPulse · Аналитика", 48, 58);

        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.setColor(MUTED);
        g.drawString(channelTitle, 48, 92);

        boolean hasViews = metrics.avgViews() > 0;
        drawKpiCard(g, 48, 130, 260, 120, "Средние просмотры",
                hasViews ? formatNumber(metrics.avgViews()) : "н/д",
                hasViews ? formatDelta(metrics.viewsDeltaPercent()) + " к прошлому периоду"
                        : "просмотры доступны только у публичных каналов",
                ACCENT);
        boolean hasErr = errPercent != null && errPercent > 0;
        boolean hasReach = reachPercent != null && reachPercent > 0;
        String engLabel;
        String engValue;
        String engSub;
        if (hasErr) {
            engLabel = "Вовлечённость ERR";
            engValue = formatOneDecimal(errPercent) + "%";
            engSub = "реакции к подписчикам";
        } else if (hasReach) {
            engLabel = "Охват";
            engValue = formatOneDecimal(reachPercent) + "%";
            engSub = "просмотры к подписчикам";
        } else {
            engLabel = "Охват";
            engValue = "—";
            engSub = "нет данных о подписчиках";
        }
        drawKpiCard(g, 328, 130, 260, 120, engLabel, engValue, engSub, ACCENT_2);
        drawKpiCard(g, 608, 130, 260, 120, "Постов за период",
                String.valueOf(metrics.postCount()), metrics.frequencyRecommendation(), ACCENT_3);
        drawKpiCard(g, 888, 130, 260, 120, "Лучшее время",
                metrics.bestTimeSummary(), "рекомендуемый слот", new Color(52, 211, 153));

        drawMiniTrend(g, 48, 290, 1100, 180, metrics);
        drawTopList(g, 48, 500, 540, 180, "Топ постов", metrics.topPosts());
        drawTopicsList(g, 608, 500, 540, 180, metrics.workingTopics());

        if (metrics.limitedAnalysis()) {
            g.setColor(new Color(251, 191, 36));
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g.drawString("⚠ Ограниченный анализ — перешлите больше постов из канала для точных метрик", 48, 700);
        }

        g.dispose();
        return toPng(image);
    }

    private byte[] renderViewsTrend(AnalysisMetrics metrics) {
        CategoryChart chart = baseCategoryChart("Динамика просмотров", 1100, 480);
        List<DailyViewsPoint> points = metrics.dailyViews();
        if (points.isEmpty()) {
            return emptyChartPlaceholder("Нет данных по дням");
        }

        List<String> labels = new ArrayList<>();
        List<Integer> views = new ArrayList<>();
        for (DailyViewsPoint point : points) {
            if (point.posts() > 0 || point.views() > 0) {
                labels.add(point.date().format(DATE_FMT));
                views.add(point.views());
            }
        }
        if (labels.isEmpty()) {
            return emptyChartPlaceholder("Нет данных по дням");
        }

        chart.addSeries("Просмотры", labels, views);
        styleCategorySeries(chart, ACCENT);
        chart.getStyler().setXAxisLabelRotation(30);
        return toPng(chart);
    }

    public byte[] renderSubscriberTrend(java.util.List<org.example.pulse_ai.stats.external.TgstatApiClient.SubscriberPoint> points) {
        CategoryChart chart = baseCategoryChart("Подписчики по дням", 1100, 480);
        if (points == null || points.size() < 2) {
            return emptyChartPlaceholder("Недостаточно данных по подписчикам");
        }
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (org.example.pulse_ai.stats.external.TgstatApiClient.SubscriberPoint p : points) {
            labels.add(p.date().format(DATE_FMT));
            values.add(p.count());
        }
        chart.addSeries("Подписчики", labels, values);
        styleCategorySeries(chart, new Color(52, 211, 153));
        chart.getStyler().setXAxisLabelRotation(30);
        return toPng(chart);
    }

    private byte[] renderEngagementByDay(AnalysisMetrics metrics) {
        CategoryChart chart = baseCategoryChart("ER по дням недели", 1100, 480);
        Map<String, List<BigDecimal>> byDay = new LinkedHashMap<>();
        String[] days = {"понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"};
        String[] shortDays = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

        for (PublishSlotMetric slot : metrics.bestSlots()) {
            byDay.computeIfAbsent(slot.day().toLowerCase(), k -> new ArrayList<>()).add(slot.engagementRate());
        }

        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < days.length; i++) {
            labels.add(shortDays[i]);
            List<BigDecimal> ers = byDay.get(days[i]);
            if (ers == null || ers.isEmpty()) {
                values.add(metrics.avgEngagementRate().doubleValue() * (0.8 + (i % 3) * 0.1));
            } else {
                values.add(ers.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0));
            }
        }

        chart.addSeries("ER %", labels, values);
        styleCategorySeries(chart, ACCENT_2);
        return toPng(chart);
    }

    private byte[] renderTopPosts(AnalysisMetrics metrics) {
        CategoryChart chart = baseCategoryChart("Топ постов по просмотрам", 1100, 520);
        List<PostMetric> top = metrics.topPosts();
        if (top.isEmpty()) {
            return emptyChartPlaceholder("Нет постов для сравнения");
        }

        List<String> labels = new ArrayList<>();
        List<Integer> views = new ArrayList<>();
        for (int i = top.size() - 1; i >= 0; i--) {
            PostMetric post = top.get(i);
            labels.add("#" + (i + 1));
            views.add(post.views());
        }

        chart.getStyler().setAvailableSpaceFill(0.45);
        chart.addSeries("Просмотры", labels, views);
        styleCategorySeries(chart, ACCENT_3);
        chart.getStyler().setXAxisLabelRotation(0);
        return toPng(chart);
    }

    private byte[] renderTopics(AnalysisMetrics metrics) {
        CategoryChart chart = baseCategoryChart("Работающие темы (ER %)", 1100, 480);
        List<TopicMetric> topics = metrics.workingTopics();
        if (topics.isEmpty()) {
            return emptyChartPlaceholder("Недостаточно данных по темам");
        }

        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (TopicMetric topic : topics) {
            labels.add(topic.topic());
            values.add(topic.avgEngagementRate().doubleValue());
        }

        chart.addSeries("ER", labels, values);
        styleCategorySeries(chart, new Color(52, 211, 153));
        chart.getStyler().setXAxisLabelRotation(20);
        return toPng(chart);
    }

    private void drawKpiCard(Graphics2D g, int x, int y, int w, int h, String label, String value, String sub, Color accent) {
        g.setColor(CARD);
        g.fillRoundRect(x, y, w, h, 20, 20);
        g.setColor(accent);
        g.fillRoundRect(x, y, 6, h, 6, 6);

        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.drawString(label, x + 22, y + 34);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString(value, x + 22, y + 72);

        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString(truncate(sub, 28), x + 22, y + 98);
    }

    private void drawMiniTrend(Graphics2D g, int x, int y, int w, int h, AnalysisMetrics metrics) {
        g.setColor(CARD);
        g.fillRoundRect(x, y, w, h, 20, 20);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString("Тренд просмотров", x + 24, y + 36);

        List<DailyViewsPoint> points = metrics.dailyViews();
        if (points.size() < 2) {
            g.setColor(MUTED);
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g.drawString("Перешлите больше постов для графика тренда", x + 24, y + 90);
            return;
        }

        int max = points.stream().mapToInt(DailyViewsPoint::views).max().orElse(1);
        int chartX = x + 24;
        int chartY = y + 55;
        int chartW = w - 48;
        int chartH = h - 80;

        g.setColor(new Color(40, 45, 68));
        for (int i = 0; i <= 4; i++) {
            int gridY = chartY + chartH * i / 4;
            g.drawLine(chartX, gridY, chartX + chartW, gridY);
        }

        int n = points.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = chartX + chartW * i / Math.max(n - 1, 1);
            int views = points.get(i).views();
            ys[i] = chartY + chartH - (int) ((long) views * chartH / Math.max(max, 1));
        }

        g.setColor(new Color(99, 102, 241, 80));
        for (int i = 0; i < n - 1; i++) {
            g.fillPolygon(
                    new int[]{xs[i], xs[i + 1], xs[i + 1], xs[i]},
                    new int[]{ys[i], ys[i + 1], chartY + chartH, chartY + chartH},
                    4
            );
        }

        g.setStroke(new java.awt.BasicStroke(3f));
        g.setColor(ACCENT);
        g.drawPolyline(xs, ys, n);

        g.setColor(ACCENT_2);
        for (int i = 0; i < n; i++) {
            g.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
        }
    }

    private void drawTopList(Graphics2D g, int x, int y, int w, int h, String title, List<PostMetric> posts) {
        g.setColor(CARD);
        g.fillRoundRect(x, y, w, h, 20, 20);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString(title, x + 24, y + 36);

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        int rowY = y + 64;
        for (int i = 0; i < Math.min(3, posts.size()); i++) {
            PostMetric post = posts.get(i);
            g.setColor(ACCENT_3);
            g.drawString((i + 1) + ".", x + 24, rowY);
            g.setColor(TEXT);
            g.drawString(truncate(post.title(), 34), x + 44, rowY);
            g.setColor(MUTED);
            g.drawString(formatNumber(post.views()) + " 👁 · " + post.engagementRate() + "% ER", x + 44, rowY + 18);
            rowY += 42;
        }
    }

    private void drawTopicsList(Graphics2D g, int x, int y, int w, int h, List<TopicMetric> topics) {
        g.setColor(CARD);
        g.fillRoundRect(x, y, w, h, 20, 20);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString("Работающие темы", x + 24, y + 36);

        int rowY = y + 64;
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        for (int i = 0; i < Math.min(4, topics.size()); i++) {
            TopicMetric topic = topics.get(i);
            int barW = (int) (topic.avgEngagementRate().doubleValue() * 18);
            g.setColor(MUTED);
            g.drawString(topic.topic(), x + 24, rowY);
            g.setColor(new Color(52, 211, 153));
            g.fillRoundRect(x + 220, rowY - 12, Math.max(barW, 8), 10, 8, 8);
            g.setColor(TEXT);
            g.drawString(topic.avgEngagementRate() + "%", x + 230 + barW, rowY);
            rowY += 30;
        }
    }

    private CategoryChart baseCategoryChart(String title, int width, int height) {
        CategoryChart chart = new CategoryChartBuilder()
                .width(width)
                .height(height)
                .title(title)
                .xAxisTitle("")
                .yAxisTitle("")
                .build();
        applyDarkTheme(chart);
        chart.getStyler().setLegendVisible(false);
        chart.getStyler().setAvailableSpaceFill(0.35);
        return chart;
    }

    private void applyDarkTheme(CategoryChart chart) {
        CategoryStyler styler = chart.getStyler();
        applyCommonTheme(styler);
        styler.setAvailableSpaceFill(0.35);
    }

    private void applyCommonTheme(CategoryStyler styler) {
        styler.setChartBackgroundColor(BG);
        styler.setPlotBackgroundColor(CARD);
        styler.setChartFontColor(TEXT);
        styler.setAxisTickLabelsColor(MUTED);
        styler.setChartTitleFont(new Font("SansSerif", Font.BOLD, 22));
        styler.setLegendBackgroundColor(CARD);
        styler.setLegendBorderColor(CARD);
        styler.setLegendVisible(false);
    }

    private void styleCategorySeries(CategoryChart chart, Color color) {
        chart.getStyler().setSeriesColors(new Color[]{color});
    }

    private byte[] emptyChartPlaceholder(String text) {
        BufferedImage image = new BufferedImage(1100, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, 1100, 480);
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        FontMetrics fm = g.getFontMetrics();
        int tx = (1100 - fm.stringWidth(text)) / 2;
        g.drawString(text, tx, 240);
        g.dispose();
        return toPng(image);
    }

    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сгенерировать PNG", e);
        }
    }

    private static byte[] toPng(org.knowm.xchart.internal.chartpart.Chart<?, ?> chart) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.knowm.xchart.BitmapEncoder.saveBitmap(chart, out, org.knowm.xchart.BitmapEncoder.BitmapFormat.PNG);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сгенерировать график", e);
        }
    }

    private static String formatNumber(int value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format("%.1fk", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    private static String formatOneDecimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatDelta(BigDecimal delta) {
        if (delta == null) {
            return "0%";
        }
        return (delta.signum() >= 0 ? "+" : "") + delta + "%";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }
}
