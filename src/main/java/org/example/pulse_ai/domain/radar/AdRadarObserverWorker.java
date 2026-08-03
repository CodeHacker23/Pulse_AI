package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.analysis.ContentPlanService;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
import org.example.pulse_ai.domain.scout.ScoutActionLogService;
import org.example.pulse_ai.domain.scout.ScoutSessionGateway;
import org.example.pulse_ai.persistence.entity.AdRadarHitEntity;
import org.example.pulse_ai.persistence.entity.AdWatchSourceEntity;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.repository.AdRadarHitRepository;
import org.example.pulse_ai.persistence.repository.AdWatchSourceRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** P2 lite: observer сканирует сохранённые чаты на ключевые слова через sidecar. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdRadarObserverWorker {

    private final PulseScoutProperties scoutProperties;
    private final AdWatchSourceRepository watchSourceRepository;
    private final AdRadarHitRepository hitRepository;
    private final ScoutAccountService scoutAccountService;
    private final ScoutSessionGateway scoutGateway;
    private final ScoutActionLogService actionLogService;
    private final ContentPlanService contentPlanService;
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void scanWatchSources() {
        if (!scoutProperties.isEnabled() || !scoutProperties.sidecarConfigured()) {
            return;
        }
        Optional<ScoutAccountEntity> observer = scoutAccountService.pickParserAccount();
        if (observer.isEmpty()) {
            return;
        }
        List<AdWatchSourceEntity> sources = watchSourceRepository.findByActiveTrue();
        for (AdWatchSourceEntity source : sources) {
            scanSource(observer.get(), source);
        }
    }

    private void scanSource(ScoutAccountEntity account, AdWatchSourceEntity source) {
        ScoutSessionGateway.ScanChatResult result = scoutGateway.scanChatKeywords(
                account.getId(),
                source.getLinkOrUsername(),
                scoutProperties.getRadarKeywords());
        if (!result.ok() || result.hits().isEmpty()) {
            if (!result.ok()) {
                actionLogService.fail(account.getId(), source.getUserId(), "CHAT_SCAN",
                        source.getLinkOrUsername(), result.error());
            }
            return;
        }
        int newHits = 0;
        for (ScoutSessionGateway.ChatHit hit : result.hits()) {
            String snippet = hit.snippet() != null ? hit.snippet().trim() : "";
            if (snippet.isEmpty()) {
                continue;
            }
            if (hitRepository.existsByUserIdAndWatchSourceIdAndSnippet(
                    source.getUserId(), source.getId(), snippet)) {
                continue;
            }
            AdRadarHitEntity entity = new AdRadarHitEntity();
            entity.setUserId(source.getUserId());
            entity.setWatchSourceId(source.getId());
            entity.setHitType("AD_SIGNAL");
            entity.setSnippet(snippet);
            entity.setStatus("NEW");
            hitRepository.save(entity);
            newHits++;
            if (source.getOwnerChannelId() != null) {
                contentPlanService.suggestFromRadar(
                        source.getOwnerChannelId(), snippet, hit.matchedKeyword());
            }
        }
        if (newHits == 0) {
            return;
        }
        actionLogService.ok(account.getId(), source.getUserId(), "CHAT_SCAN",
                source.getLinkOrUsername() + " → +" + newHits);
        int notifyCount = newHits;
        userRepository.findById(source.getUserId()).ifPresent(user -> {
            if (user.getTelegramId() != null) {
                messageSender.sendTextSafe(user.getTelegramId(),
                        "📡 <b>Ad Radar</b>: в " + source.getLinkOrUsername()
                                + " найдено " + notifyCount + " новых сигнал(ов) про рекламу.\n"
                                + "Откройте Pulse Ассистент → Рост и реклама → сигналы.");
            }
        });
    }
}
