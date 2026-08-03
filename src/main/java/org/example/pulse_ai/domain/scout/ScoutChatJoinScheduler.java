package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Медленный join из общего пула: один чат за тик (~30с).
 * Не вступает скопом в 200–400 групп — это классический путь к бану.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoutChatJoinScheduler {

    private final ScoutChatPoolService chatPoolService;

    @Scheduled(fixedDelayString = "${pulse.scout.join-interval-ms:30000}", initialDelay = 45_000)
    public void tick() {
        try {
            chatPoolService.processOneJoin().ifPresent(msg -> log.info("chat-pool: {}", msg));
        } catch (Exception ex) {
            log.warn("chat-pool tick failed: {}", ex.getMessage());
        }
    }
}
