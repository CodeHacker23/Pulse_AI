package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GroupParseWorker {

    private final GroupMemberParseService parseService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        parseService.processPendingJobs();
    }
}
