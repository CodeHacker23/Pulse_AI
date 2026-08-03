package org.example.pulse_ai.domain.scout;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class NoOpScoutSessionGateway implements ScoutSessionGateway {

    private static final String MSG = "Sidecar не настроен (pulse.scout.sidecar-url)";

    @Override
    public SendResult sendDirectMessage(long scoutAccountId, String username, String text) {
        return SendResult.failed(MSG);
    }

    @Override
    public ParseMembersResult parseGroupMembers(long scoutAccountId, String groupLink, int limit) {
        return ParseMembersResult.failed(MSG);
    }

    @Override
    public ParseAudienceResult parseAudience(long scoutAccountId, String groupLink, int limit, int minScore) {
        return ParseAudienceResult.failed(MSG);
    }

    @Override
    public ScanChatResult scanChatKeywords(long scoutAccountId, String chatLink, List<String> keywords) {
        return ScanChatResult.failed(MSG);
    }

    @Override
    public JoinResult joinChat(long scoutAccountId, String link) {
        return JoinResult.failed(MSG);
    }

    @Override
    public VacuumResult vacuumPosts(long scoutAccountId, String link, int limit) {
        return VacuumResult.failed(MSG);
    }

    @Override
    public SimpleResult spamBotStart(long scoutAccountId) {
        return SimpleResult.failed(MSG);
    }

    @Override
    public SimpleResult rotateProxy(long scoutAccountId) {
        return SimpleResult.failed(MSG);
    }

    @Override
    public SimpleResult assignProxy(long scoutAccountId) {
        return SimpleResult.failed(MSG);
    }

    @Override
    public ProxyImportResult importProxies(String text) {
        return new ProxyImportResult(false, 0, 0, 0, MSG);
    }

    @Override
    public ProxyListResult listProxies() {
        return new ProxyListResult(false, List.of(), Map.of(), MSG);
    }

    @Override
    public SimpleResult purgeInvalidProxies() {
        return SimpleResult.failed(MSG);
    }
}
