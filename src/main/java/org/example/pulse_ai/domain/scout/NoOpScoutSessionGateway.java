package org.example.pulse_ai.domain.scout;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class NoOpScoutSessionGateway implements ScoutSessionGateway {

    private static final String MSG = "Sidecar не настроен (pulse.scout.sidecar-url)";

    @Override
    public SendResult sendDirectMessage(long scoutAccountId, String username, String text) {
        log.debug("NoOp DM @{} via account {}: {}", username, scoutAccountId, MSG);
        return SendResult.failed(MSG);
    }

    @Override
    public ParseMembersResult parseGroupMembers(long scoutAccountId, String groupLink, int limit) {
        log.debug("NoOp parse {} via account {}: {}", groupLink, scoutAccountId, MSG);
        return ParseMembersResult.failed(MSG);
    }

    @Override
    public ScanChatResult scanChatKeywords(long scoutAccountId, String chatLink, List<String> keywords) {
        log.debug("NoOp scan {} via account {}: {}", chatLink, scoutAccountId, MSG);
        return ScanChatResult.failed(MSG);
    }
}
