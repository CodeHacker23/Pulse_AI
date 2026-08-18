package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.audience.AudienceBrief;
import org.example.pulse_ai.domain.audience.AudienceBriefGrounding;
import org.example.pulse_ai.domain.audience.AudienceIntelService;
import org.example.pulse_ai.domain.audience.AudienceLexicon;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelProfileSnapshotEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelProfileSnapshotRepository;
import org.example.pulse_ai.stats.external.ExternalChannelMetrics;
import org.example.pulse_ai.stats.external.NicheComparison;
import org.example.pulse_ai.stats.external.TgstatAccessService;
import org.example.pulse_ai.stats.external.TgstatApiClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Подбор площадок: несколько запросов из профиля ЦА, потом отсев по токенам и размеру.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdPlacementMatchService {

    private static final int MAX_RESULTS = 6;

    private final ChannelProfileSnapshotRepository profileRepository;
    private final TgstatApiClient tgstatApiClient;
    private final TgstatAccessService tgstatAccessService;
    private final AdRadarService adRadarService;
    private final AudienceIntelService audienceIntelService;

    public MatchResult matchForChannel(UserEntity user, ChannelEntity owner) {
        AudienceBrief brief = audienceIntelService.resolve(owner);
        String me = owner.getUsername() != null ? owner.getUsername().toLowerCase(Locale.ROOT) : "";
        boolean useTgstat = tgstatAccessService.forPlacementSearch(user.getId());

        if (!brief.usable()) {
            return MatchResult.empty("""
                    ЦА ещё не собрана: мало своих постов или только общее имя канала.
                    Разберите канал (ссылка → анализ) — бот вытащит темы из текстов, не из ярлыка каталога.
                    Пока можно проверить конкретный @канал в «⋯ Ещё».""");
        }

        Map<String, PeerHint> peers = new LinkedHashMap<>();
        if (useTgstat) {
            for (String q : brief.searchQueries()) {
                for (NicheComparison.Peer p : tgstatApiClient.searchPeers(q, me, 12)) {
                    addPeer(peers, p.title(), p.username(), p.subscribers(), "TGStat · «" + q + "»", brief);
                }
            }
            String niche = owner.getCategory();
            if (niche != null && !AudienceLexicon.tooBroadLabel(niche) && !AudienceLexicon.tooBroadAloneQuery(niche)) {
                try {
                    int mySubs = owner.getSubscriberCount() != null ? owner.getSubscriberCount() : 0;
                    tgstatApiClient.compareNiche(niche, mySubs, me).ifPresent(n -> {
                        for (NicheComparison.Peer p : n.similar()) {
                            addPeer(peers, p.title(), p.username(), p.subscribers(), "похожий размер в нише", brief);
                        }
                    });
                } catch (Exception ex) {
                    log.debug("compareNiche skip: {}", ex.getMessage());
                }
            }
        }

        String niche = owner.getCategory();
        if (niche != null && !AudienceLexicon.tooBroadLabel(niche) && !AudienceLexicon.tooBroadAloneQuery(niche)) {
            for (ChannelProfileSnapshotEntity snap : profileRepository.findByCategoryOrderByAnalyzedAtDesc(niche)) {
                if (snap.getUsername() == null || snap.getUsername().isBlank()) {
                    continue;
                }
                String u = snap.getUsername().replace("@", "").toLowerCase(Locale.ROOT);
                if (u.equals(me) || owner.getId().equals(snap.getChannelId())) {
                    continue;
                }
                int subs = snap.getSubscriberCount() != null ? snap.getSubscriberCount() : 0;
                addPeer(peers, snap.getTitle(), u, subs, "из базы Pulse", brief);
                if (peers.size() >= 24) {
                    break;
                }
            }
        }

        if (peers.isEmpty()) {
            boolean apiOn = tgstatApiClient.isEnabled();
            StringBuilder why = new StringBuilder();
            if (!apiOn) {
                why.append("TGStat API не подключён.\n");
            } else if (!useTgstat) {
                why.append("Поиск площадок через TGStat — в тарифах CONTENT и PRO.\n");
            } else {
                why.append("По запросам «").append(brief.queryLabel()).append("» TGStat ничего не вернул.\n");
            }
            why.append("ЦА: ").append(brief.summaryLine()).append("\n");
            why.append("• разберите канал ещё раз, если постов стало больше\n");
            why.append("• или проверьте @площадку вручную");
            return MatchResult.empty(why.toString());
        }

        List<PeerHint> ranked = new ArrayList<>(peers.values());
        ranked.sort(Comparator.comparingInt(PeerHint::overlap).reversed());

        List<ScoredPlacement> scored = new ArrayList<>();
        int n = 0;
        for (PeerHint hint : ranked) {
            if (n >= MAX_RESULTS) {
                break;
            }
            n++;
            Integer reach = hint.avgViews();
            if (useTgstat) {
                try {
                    Optional<ExternalChannelMetrics> stat = tgstatApiClient.getStat(hint.username());
                    if (stat.isPresent() && stat.get().avgReach() != null) {
                        reach = stat.get().avgReach();
                    }
                } catch (Exception ignored) {
                    // ориентир без точного охвата
                }
            }
            int price = AdRadarService.estimatePostPrice(hint.subscribers(), reach);
            AdPlacementEntity saved = adRadarService.upsertCandidate(
                    user,
                    owner.getId(),
                    hint.username(),
                    hint.title(),
                    hint.subscribers(),
                    reach,
                    price,
                    hint.reason()
            );
            scored.add(new ScoredPlacement(saved, hint.reason(), hint.subscribers(), price));
        }

        return new MatchResult(brief.queryLabel(), scored, null, brief.summaryLine());
    }

    private static void addPeer(
            Map<String, PeerHint> peers,
            String title,
            String username,
            int subscribers,
            String reason,
            AudienceBrief brief
    ) {
        if (username == null || username.isBlank()) {
            return;
        }
        if (!AudienceBriefGrounding.sizeOk(subscribers, brief.minSubs(), brief.maxSubs())) {
            return;
        }
        String u = username.toLowerCase(Locale.ROOT);
        int overlap = overlapScore(title, username, brief);
        peers.putIfAbsent(u, new PeerHint(title, u, subscribers, null, reason, overlap));
    }

    private static int overlapScore(String title, String username, AudienceBrief brief) {
        String blob = AudienceLexicon.norm((title == null ? "" : title) + " " + (username == null ? "" : username));
        int score = 0;
        List<String> needles = new ArrayList<>();
        if (brief.searchQueries() != null) {
            needles.addAll(brief.searchQueries());
        }
        for (String q : needles) {
            for (String part : AudienceLexicon.norm(q).split("\\s+")) {
                if (part.length() >= 4 && blob.contains(part)) {
                    score++;
                }
            }
        }
        return score;
    }

    private record PeerHint(
            String title,
            String username,
            int subscribers,
            Integer avgViews,
            String reason,
            int overlap
    ) {
    }

    public record ScoredPlacement(
            AdPlacementEntity placement,
            String matchReason,
            int subscribersHint,
            int estimatedPriceRub
    ) {
    }

    public record MatchResult(String category, List<ScoredPlacement> placements, String emptyMessage, String audienceLine) {
        static MatchResult empty(String msg) {
            return new MatchResult(null, List.of(), msg, null);
        }

        public boolean isEmpty() {
            return placements == null || placements.isEmpty();
        }
    }
}
