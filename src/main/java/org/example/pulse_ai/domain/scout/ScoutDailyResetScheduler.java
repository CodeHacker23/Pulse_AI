package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScoutDailyResetScheduler {

    private final ScoutAccountService scoutAccountService;

    @Scheduled(cron = "0 5 0 * * *", zone = "Europe/Moscow")
    public void resetCounters() {
        scoutAccountService.resetDailyCounters();
    }
}
