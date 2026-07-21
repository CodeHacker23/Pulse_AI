package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
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
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void scanWatchSources() {
        if (!scoutProperties.isEnabled() || !scoutProperties.sidecarConfigured()) {
            return;
        }
        Optional<ScoutAccountEntity> observer = scoutAccountService.listAll().stream()
                .filter(a -> "OBSERVER".equals(a.getAccountType()) && "ACTIVE".equals(a.getStatus()))
                .findFirst();
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
            return;
        }
        for (ScoutSessionGateway.ChatHit hit : result.hits()) {
            AdRadarHitEntity entity = new AdRadarHitEntity();
            entity.setUserId(source.getUserId());
            entity.setWatchSourceId(source.getId());
            entity.setHitType("AD_SIGNAL");
            entity.setSnippet(hit.snippet());
            entity.setStatus("NEW");
            hitRepository.save(entity);
        }
        userRepository.findById(source.getUserId()).ifPresent(user -> {
            if (user.getTelegramId() != null) {
                messageSender.sendTextSafe(user.getTelegramId(),
                        "📡 <b>Ad Radar</b>: в " + source.getLinkOrUsername()
                                + " найдено " + result.hits().size() + " сигнал(ов) про рекламу.\n"
                                + "Откройте Jarvis → Ad Radar → сигналы.");
            }
        });
    }
}
