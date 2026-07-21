package org.example.pulse_ai.domain.schedule;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.SlotPerformanceEntity;
import org.example.pulse_ai.persistence.repository.SlotPerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Обучение на охватах: копит фактическую эффективность слотов публикации
 * и отдаёт множитель, которым переранжируются рекомендации времени.
 */
@Service
@RequiredArgsConstructor
public class SlotPerformanceService {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final double MIN_MULTIPLIER = 0.4;
    private static final double MAX_MULTIPLIER = 1.8;

    private final SlotPerformanceRepository repository;

    /** Ключ слота: "<день недели>|<HH:mm>" — совпадает с ключом из AnalyticsService. */
    public static String slotKey(Instant publishedAt) {
        var zdt = publishedAt.atZone(MOSCOW);
        String day = zdt.getDayOfWeek().getDisplayName(TextStyle.FULL, RU);
        int hour = zdt.getHour();
        String bucket = hour < 12 ? "09:00" : hour < 17 ? "14:00" : "19:00";
        return day + "|" + bucket;
    }

    public static String slotKey(String day, String time) {
        return day + "|" + time;
    }

    public static DayOfWeek parseDay(String day) {
        if (day == null) {
            return null;
        }
        return switch (day.trim().toLowerCase(RU)) {
            case "понедельник" -> DayOfWeek.MONDAY;
            case "вторник" -> DayOfWeek.TUESDAY;
            case "среда" -> DayOfWeek.WEDNESDAY;
            case "четверг" -> DayOfWeek.THURSDAY;
            case "пятница" -> DayOfWeek.FRIDAY;
            case "суббота" -> DayOfWeek.SATURDAY;
            case "воскресенье" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** Множитель для переранжирования слота (1.0 — нет данных/нейтрально). */
    @Transactional(readOnly = true)
    public double multiplier(Long channelId, String slotKey) {
        return repository.findByChannelIdAndSlotKey(channelId, slotKey)
                .filter(s -> s.getSampleCount() >= 1)
                .map(s -> clamp(s.getAvgRatio()))
                .orElse(1.0);
    }

    /** Обновляет накопленную статистику слота новым наблюдением ratio = факт/среднее. */
    @Transactional
    public void record(Long channelId, String slotKey, double ratio) {
        SlotPerformanceEntity slot = repository.findByChannelIdAndSlotKey(channelId, slotKey)
                .orElseGet(() -> {
                    SlotPerformanceEntity s = new SlotPerformanceEntity();
                    s.setChannelId(channelId);
                    s.setSlotKey(slotKey);
                    s.setSampleCount(0);
                    s.setAvgRatio(1.0);
                    return s;
                });
        int n = slot.getSampleCount();
        double newAvg = (slot.getAvgRatio() * n + ratio) / (n + 1);
        slot.setSampleCount(n + 1);
        slot.setAvgRatio(newAvg);
        repository.save(slot);
    }

    private static double clamp(double v) {
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, v));
    }
}
