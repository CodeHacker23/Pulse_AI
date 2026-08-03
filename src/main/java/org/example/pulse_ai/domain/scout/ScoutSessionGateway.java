package org.example.pulse_ai.domain.scout;

import java.util.List;
import java.util.Map;

/** MTProto-sidecar: ЛС (только SENDER), парсинг (PARSER), прокси, SpamBot. */
public interface ScoutSessionGateway {

    SendResult sendDirectMessage(long scoutAccountId, String username, String text);

    ParseMembersResult parseGroupMembers(long scoutAccountId, String groupLink, int limit);

    /** Парсинг с отсевом мёртвых/накрутки (minScore, по умолчанию 35 = warm+). */
    ParseAudienceResult parseAudience(long scoutAccountId, String groupLink, int limit, int minScore);

    ScanChatResult scanChatKeywords(long scoutAccountId, String chatLink, List<String> keywords);

    JoinResult joinChat(long scoutAccountId, String link);

    VacuumResult vacuumPosts(long scoutAccountId, String link, int limit);

    SimpleResult spamBotStart(long scoutAccountId);

    SimpleResult rotateProxy(long scoutAccountId);

    SimpleResult assignProxy(long scoutAccountId);

    ProxyImportResult importProxies(String text);

    ProxyListResult listProxies();

    SimpleResult purgeInvalidProxies();

    record SendResult(boolean ok, String error, Long messageId) {
        public static SendResult success(Long messageId) {
            return new SendResult(true, null, messageId);
        }

        public static SendResult failed(String error) {
            return new SendResult(false, error, null);
        }
    }

    record ParseMembersResult(boolean ok, List<String> usernames, String error) {
        public static ParseMembersResult failed(String error) {
            return new ParseMembersResult(false, List.of(), error);
        }
    }

    record AudienceMember(String username, int score, String tier) {
    }

    record ParseAudienceResult(boolean ok, List<AudienceMember> users, String error) {
        public static ParseAudienceResult failed(String error) {
            return new ParseAudienceResult(false, List.of(), error);
        }
    }

    record ScanChatResult(boolean ok, List<ChatHit> hits, String error) {
        public static ScanChatResult failed(String error) {
            return new ScanChatResult(false, List.of(), error);
        }
    }

    record ChatHit(String snippet, String matchedKeyword) {
    }

    record JoinResult(boolean ok, String title, String error) {
        public static JoinResult failed(String error) {
            return new JoinResult(false, null, error);
        }
    }

    record VacuumResult(boolean ok, List<Map<String, Object>> posts, String error) {
        public static VacuumResult failed(String error) {
            return new VacuumResult(false, List.of(), error);
        }
    }

    record SimpleResult(boolean ok, String detail, String error) {
        public static SimpleResult ok(String detail) {
            return new SimpleResult(true, detail, null);
        }

        public static SimpleResult failed(String error) {
            return new SimpleResult(false, null, error);
        }
    }

    record ProxyImportResult(boolean ok, int added, int total, int valid, String error) {
    }

    record ProxyListResult(boolean ok, List<Map<String, Object>> proxies, Map<String, Object> assignments, String error) {
    }
}
