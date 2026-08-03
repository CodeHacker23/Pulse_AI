package org.example.pulse_ai.domain.user;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.UserSettingsEntity;
import org.example.pulse_ai.persistence.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserTimezoneService {

    public static final String DEFAULT_ZONE = "Europe/Moscow";

    /** Частые пояса РФ для кнопок в Кабинете. */
    public static final List<ZoneOption> PRESETS = List.of(
            new ZoneOption("Europe/Kaliningrad", "Калининград", "Клд"),
            new ZoneOption("Europe/Moscow", "Москва", "МСК"),
            new ZoneOption("Europe/Samara", "Самара", "Сам"),
            new ZoneOption("Asia/Yekaterinburg", "Екатеринбург", "Екб"),
            new ZoneOption("Asia/Omsk", "Омск", "Омск"),
            new ZoneOption("Asia/Novosibirsk", "Новосибирск", "Нск"),
            new ZoneOption("Asia/Krasnoyarsk", "Красноярск", "Крс"),
            new ZoneOption("Asia/Irkutsk", "Иркутск", "Ирк"),
            new ZoneOption("Asia/Vladivostok", "Владивосток", "Влд")
    );

    private final UserSettingsRepository settingsRepository;

    @Transactional(readOnly = true)
    public ZoneId zoneOf(long userId) {
        return settingsRepository.findById(userId)
                .map(UserSettingsEntity::getTimezone)
                .map(UserTimezoneService::parseZone)
                .orElse(ZoneId.of(DEFAULT_ZONE));
    }

    @Transactional(readOnly = true)
    public String zoneIdOf(long userId) {
        return zoneOf(userId).getId();
    }

    @Transactional
    public void setTimezone(long userId, String zoneId) {
        ZoneId zone = parseZone(zoneId);
        UserSettingsEntity s = settingsRepository.findById(userId).orElseGet(() -> {
            UserSettingsEntity n = new UserSettingsEntity();
            n.setUserId(userId);
            n.setNotificationsEnabled(true);
            return n;
        });
        s.setTimezone(zone.getId());
        settingsRepository.save(s);
    }

    public static String shortLabel(ZoneId zone) {
        String id = zone.getId();
        for (ZoneOption o : PRESETS) {
            if (o.zoneId().equals(id)) {
                return o.shortLabel();
            }
        }
        if (id.startsWith("Asia/") || id.startsWith("Europe/")) {
            return id.substring(id.indexOf('/') + 1);
        }
        return id;
    }

    public static String displayName(ZoneId zone) {
        String id = zone.getId();
        for (ZoneOption o : PRESETS) {
            if (o.zoneId().equals(id)) {
                return o.city();
            }
        }
        return id;
    }

    public static Map<String, String> presetMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (ZoneOption o : PRESETS) {
            m.put(o.zoneId(), o.city() + " (" + o.shortLabel() + ")");
        }
        return m;
    }

    private static ZoneId parseZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (DateTimeException ex) {
            return ZoneId.of(DEFAULT_ZONE);
        }
    }

    public record ZoneOption(String zoneId, String city, String shortLabel) {
    }
}
