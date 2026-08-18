package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Снимает истёкший FLOOD_WAIT → ACTIVE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoutQuarantineScheduler {

    private final ScoutAccountService scoutAccountService;

    @Scheduled(fixedDelayString = "${pulse.scout.quarantine-check-ms:60000}", initialDelay = 60_000)
    public void tick() {
        try {
            int n = scoutAccountService.releaseExpiredQuarantines();
            if (n > 0) {
                log.info("quarantine released: {} scout(s) → ACTIVE", n);
            }
        } catch (Exception ex) {
            log.warn("quarantine tick failed: {}", ex.getMessage());
        }
    }
}
