package org.example.pulse_ai.domain.audience;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Тема канала (ниша) → запросы для поиска площадок/чатов.
 * Слова из карточки товара (ноты, ваниль, основа) в поиск не идут.
 */
public final class AudienceBriefGrounding {

    private AudienceBriefGrounding() {
    }

    public static AudienceBrief fromEvidence(AudienceEvidence evidence, int ownerSubs) {
        String blob = evidence.blob();
        List<String> queries = groundedQueries(evidence, List.of());
        String theme = queries.isEmpty() ? "" : queries.get(0);
        int confidence = Math.min(70, 20 + evidence.postsUsed() * 4 + queries.size() * 8);
        String role = evidence.thin() && queries.isEmpty()
                ? "мало своих постов — роль ЦА не вывести"
                : "покупатели ниши «" + (theme.isBlank() ? evidence.queryHint() : theme) + "»";
        return sized(new AudienceBrief(
                role,
                evidence.thin() ? "" : "ищут " + (theme.isBlank() ? evidence.queryHint() : theme),
                evidence.tokens(),
                queries,
                defaultVenues(theme),
                "GROUP_MEMBERS",
                0,
                0,
                confidence,
                evidence.thin() ? "TITLE" : "POSTS",
                theme
        ), ownerSubs);
    }

    public static AudienceBrief mergeLlm(AudienceEvidence evidence, JsonNode llm, int ownerSubs) {
        AudienceBrief base = fromEvidence(evidence, ownerSubs);
        if (llm == null || llm.isMissingNode() || llm.isNull()) {
            return base;
        }
        List<String> proposed = new ArrayList<>();
        String theme = clip(llm.path("theme").asText(""), 40);
        if (!theme.isBlank()) {
            proposed.add(theme);
        }
        for (JsonNode n : llm.path("search_queries")) {
            proposed.add(n.asText(""));
        }
        List<String> queries = groundedQueries(evidence, proposed);
        if (queries.isEmpty()) {
            queries = base.searchQueries();
        }
        if (theme.isBlank() || AudienceLexicon.badSearchQuery(theme)) {
            theme = queries.isEmpty() ? base.theme() : queries.get(0);
        }

        String role = clip(llm.path("buyer_role").asText(""), 120);
        if (role.isBlank() || AudienceLexicon.tooBroadAloneQuery(role) || AudienceLexicon.tooBroadLabel(role)) {
            role = base.buyerRole();
        }

        String job = clip(llm.path("job").asText(""), 160);
        List<String> venues = new ArrayList<>();
        for (JsonNode n : llm.path("parse_venues")) {
            String v = clip(n.asText(""), 80);
            if (!v.isBlank() && !AudienceLexicon.tooBroadAloneQuery(v) && !AudienceLexicon.recipeJargon(v)) {
                venues.add(v);
            }
            if (venues.size() >= 3) {
                break;
            }
        }
        if (venues.isEmpty()) {
            venues = defaultVenues(theme);
        }
        String method = normalizeMethod(llm.path("parse_method").asText("GROUP_MEMBERS"));
        return sized(new AudienceBrief(
                role,
                job.isBlank() ? base.jobToBeDone() : job,
                evidence.tokens(),
                queries,
                venues,
                method,
                0,
                0,
                Math.min(92, base.confidence() + 15),
                "LLM_GROUNDED",
                theme
        ), ownerSubs);
    }

    public static List<String> groundedQueries(AudienceEvidence evidence, List<String> proposed) {
        String blob = evidence == null ? "" : evidence.blob();
        List<String> tokens = evidence == null ? List.of() : evidence.tokens();
        Set<String> out = new LinkedHashSet<>();
        for (String q : AudienceLexicon.familyQueries(blob)) {
            addQuery(out, q);
        }
        for (String q : proposed) {
            if (queryAllowed(q, blob, tokens)) {
                addQuery(out, q);
            }
        }
        for (String t : tokens) {
            if (queryAllowed(t, blob, tokens)) {
                addQuery(out, t);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** Старый тест: список токенов без about. */
    public static List<String> groundedQueries(List<String> evidenceTokens, List<String> proposed) {
        AudienceEvidence ev = new AudienceEvidence(
                evidenceTokens == null ? List.of() : evidenceTokens,
                List.of(),
                "",
                "",
                "",
                null,
                evidenceTokens == null ? 0 : evidenceTokens.size()
        );
        return groundedQueries(ev, proposed);
    }

    public static boolean queryGrounded(String query, List<String> evidenceTokens) {
        return queryAllowed(query, String.join(" ", evidenceTokens == null ? List.of() : evidenceTokens),
                evidenceTokens);
    }

    static boolean queryAllowed(String query, String blob, List<String> tokens) {
        if (query == null || AudienceLexicon.badSearchQuery(query)) {
            return false;
        }
        String n = AudienceLexicon.norm(query);
        if (AudienceLexicon.inBlob(n, blob) || AudienceLexicon.inDetectedFamily(n, blob)) {
            return true;
        }
        if (tokens != null) {
            for (String e : tokens) {
                if (AudienceLexicon.sameStem(n, e) || AudienceLexicon.norm(e).contains(n)
                        || n.contains(AudienceLexicon.norm(e))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Для ранжирования. Жёсткий отсев по словам карточки товара не делаем:
     * канал «Парфюм» не обязан содержать «ваниль» в названии.
     */
    public static boolean peerMatches(String title, String username, AudienceBrief brief) {
        return true;
    }

    static int[] sizeBand(int ownerSubs) {
        int min = 400;
        int max = 250_000;
        if (ownerSubs > 0) {
            min = Math.max(300, ownerSubs / 20);
            max = Math.min(300_000, Math.max(80_000, ownerSubs * 20));
        }
        return new int[]{min, max};
    }

    public static boolean sizeOk(int theirSubs, int min, int max) {
        if (theirSubs <= 0) {
            return true;
        }
        return theirSubs >= min && theirSubs <= max;
    }

    private static void addQuery(Set<String> out, String q) {
        if (out.size() >= 5) {
            return;
        }
        String n = AudienceLexicon.norm(q);
        if (n.isBlank() || AudienceLexicon.badSearchQuery(n)) {
            return;
        }
        out.add(n);
    }

    private static List<String> defaultVenues(String theme) {
        if (theme == null || theme.isBlank()) {
            return List.of();
        }
        return List.of(
                "каналы про " + theme,
                "чаты и группы по теме «" + theme + "»"
        );
    }

    private static AudienceBrief sized(AudienceBrief b, int ownerSubs) {
        int[] band = sizeBand(ownerSubs);
        return new AudienceBrief(
                b.buyerRole(),
                b.jobToBeDone(),
                b.evidenceTokens(),
                b.searchQueries(),
                b.parseVenues(),
                b.parseMethod(),
                band[0],
                band[1],
                b.confidence(),
                b.source(),
                b.theme()
        );
    }

    private static String normalizeMethod(String raw) {
        String u = raw == null ? "" : raw.trim().toUpperCase();
        return switch (u) {
            case "GROUP_MEMBERS", "CHANNEL_COMMENTS", "LOOKALIKE_CHANNELS" -> u;
            default -> "GROUP_MEMBERS";
        };
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
