package org.example.pulse_ai.domain.scout;

import java.util.List;

/** MTProto-sidecar: отправка ЛС, парсинг групп, скан чатов. */
public interface ScoutSessionGateway {

    SendResult sendDirectMessage(long scoutAccountId, String username, String text);

    ParseMembersResult parseGroupMembers(long scoutAccountId, String groupLink, int limit);

    ScanChatResult scanChatKeywords(long scoutAccountId, String chatLink, List<String> keywords);

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

    record ScanChatResult(boolean ok, List<ChatHit> hits, String error) {
        public static ScanChatResult failed(String error) {
            return new ScanChatResult(false, List.of(), error);
        }
    }

    record ChatHit(String snippet, String matchedKeyword) {
    }
}
