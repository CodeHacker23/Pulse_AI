package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.radar.AdPlacementQualityService.QualityReport;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.AdWatchSourceEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AdPlacementRepository;
import org.example.pulse_ai.persistence.repository.AdWatchSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdRadarService {

    private static final int MAX_WATCH_SOURCES = 20;
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{3,31}$");

    private final AdWatchSourceRepository watchSourceRepository;
    private final AdPlacementRepository placementRepository;
    private final AdPlacementQualityService qualityService;

    @Transactional(readOnly = true)
    public List<AdWatchSourceEntity> activeWatches(Long userId) {
        return watchSourceRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<AdPlacementEntity> recentPlacements(Long userId) {
        return placementRepository.findTop15ByUserIdOrderByLastCheckedAtDescCreatedAtDesc(userId);
    }

    @Transactional
    public AdWatchSourceEntity addWatchSource(UserEntity user, Long ownerChannelId, String rawLink) {
        if (watchSourceRepository.countByUserIdAndActiveTrue(user.getId()) >= MAX_WATCH_SOURCES) {
            throw new IllegalStateException("Лимит чатов на мониторинг: " + MAX_WATCH_SOURCES);
        }
        ParsedLink parsed = parseLink(rawLink);
        AdWatchSourceEntity entity = new AdWatchSourceEntity();
        entity.setUserId(user.getId());
        entity.setOwnerChannelId(ownerChannelId);
        entity.setLinkOrUsername(parsed.normalized());
        entity.setTitle(parsed.displayTitle());
        entity.setSourceType(parsed.chatLike() ? "CHAT" : "CHANNEL");
        entity.setNotes("Observer подключит автоматический мониторинг ключевых слов (P2).");
        return watchSourceRepository.save(entity);
    }

    @Transactional
    public AdPlacementEntity checkAndSavePlacement(UserEntity user, Long ownerChannelId, String rawInput) {
        ParsedLink parsed = parseLink(rawInput);
        if (!USERNAME.matcher(parsed.username()).matches()) {
            throw new IllegalArgumentException("Некорректная ссылка. Пример: @channel или t.me/channel");
        }

        QualityReport report = qualityService.scorePublicChannel(parsed.normalized());
        if (!report.ok()) {
            throw new IllegalStateException(report.error());
        }

        AdPlacementEntity entity = placementRepository
                .findTop15ByUserIdOrderByLastCheckedAtDescCreatedAtDesc(user.getId()).stream()
                .filter(p -> parsed.username().equalsIgnoreCase(p.getTargetUsername()))
                .findFirst()
                .orElseGet(AdPlacementEntity::new);

        entity.setUserId(user.getId());
        entity.setOwnerChannelId(ownerChannelId);
        entity.setTargetUsername(report.username());
        entity.setTargetTitle(report.title());
        entity.setScrapedChannelId(report.scrapedChannelId());
        entity.setQualityVerdict(report.verdict());
        entity.setQualityScore(report.score());
        entity.setQualityNotes(report.notes());
        entity.setPostsLast30d(report.postsLast30d());
        entity.setAdRatioPercent(report.adRatioPercent());
        entity.setAvgViews(report.avgViews());
        entity.setLastCheckedAt(Instant.now());
        return placementRepository.save(entity);
    }

    @Transactional
    public Optional<AdPlacementEntity> recheckPlacement(Long userId, Long placementId) {
        return placementRepository.findByIdAndUserId(placementId, userId).map(existing -> {
            QualityReport report = qualityService.scorePublicChannel("@" + existing.getTargetUsername());
            if (!report.ok()) {
                throw new IllegalStateException(report.error());
            }
            existing.setQualityVerdict(report.verdict());
            existing.setQualityScore(report.score());
            existing.setQualityNotes(report.notes());
            existing.setPostsLast30d(report.postsLast30d());
            existing.setAdRatioPercent(report.adRatioPercent());
            existing.setAvgViews(report.avgViews());
            existing.setLastCheckedAt(Instant.now());
            return placementRepository.save(existing);
        });
    }

    private static ParsedLink parseLink(String raw) {
        String trimmed = raw.trim();
        String username = trimmed;
        if (username.startsWith("https://t.me/")) {
            username = username.substring("https://t.me/".length());
        }
        if (username.startsWith("@")) {
            username = username.substring(1);
        }
        int slash = username.indexOf('/');
        if (slash > 0) {
            username = username.substring(0, slash);
        }
        int q = username.indexOf('?');
        if (q > 0) {
            username = username.substring(0, q);
        }
        username = username.toLowerCase(Locale.ROOT);
        return new ParsedLink(username, "@" + username, username.contains("chat") || username.endsWith("group"));
    }

    private record ParsedLink(String username, String normalized, boolean chatLike) {
        String displayTitle() {
            return normalized;
        }
    }
}
