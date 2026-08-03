package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Подбор площадок под канал пользователя.
 * Не требует полного скрейпа каждого кандидата — сначала быстрые карточки из TGStat/базы Pulse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdPlacementMatchService {

    private static final int MAX_RESULTS = 6;
    private static final Pattern NOISE = Pattern.compile("[^\\p{L}\\p{N}\\s]+");

    private final ChannelProfileSnapshotRepository profileRepository;
    private final TgstatApiClient tgstatApiClient;
    private final TgstatAccessService tgstatAccessService;
    private final AdRadarService adRadarService;

    public MatchResult matchForChannel(UserEntity user, ChannelEntity owner) {
        String niche = resolveNicheLabel(owner);
        String searchQuery = resolveSearchQuery(owner, niche);
        String me = owner.getUsername() != null ? owner.getUsername().toLowerCase(Locale.ROOT) : "";
        int mySubs = owner.getSubscriberCount() != null ? owner.getSubscriberCount() : 0;

        Map<String, PeerHint> peers = new LinkedHashMap<>();
        boolean useTgstat = tgstatAccessService.forPlacementSearch(user.getId());

        // 1) TGStat по строке поиска (название / ниша) — CONTENT+
        if (useTgstat) {
            for (NicheComparison.Peer p : tgstatApiClient.searchPeers(searchQuery, me, 20)) {
                addPeer(peers, p, "TGStat · «" + searchQuery + "»");
            }

            // 2) TGStat по категории, если есть
            if (niche != null && !niche.equalsIgnoreCase(searchQuery)) {
                for (NicheComparison.Peer p : tgstatApiClient.searchPeers(niche, me, 15)) {
                    addPeer(peers, p, "ниша «" + niche + "»");
                }
                try {
                    tgstatApiClient.compareNiche(niche, mySubs, me).ifPresent(n -> {
                        for (NicheComparison.Peer p : n.similar()) {
                            addPeer(peers, p, "похожий размер в нише");
                        }
                    });
                } catch (Exception ex) {
                    log.debug("compareNiche skip: {}", ex.getMessage());
                }
            }
        }

        // 3) Локальная база разборов Pulse
        if (niche != null) {
            for (ChannelProfileSnapshotEntity snap : profileRepository.findByCategoryOrderByAnalyzedAtDesc(niche)) {
                if (snap.getUsername() == null || snap.getUsername().isBlank()) {
                    continue;
                }
                String u = snap.getUsername().replace("@", "").toLowerCase(Locale.ROOT);
                if (u.equals(me) || owner.getId().equals(snap.getChannelId())) {
                    continue;
                }
                peers.putIfAbsent(u, new PeerHint(
                        snap.getTitle() != null ? snap.getTitle() : u,
                        u,
                        snap.getSubscriberCount() != null ? snap.getSubscriberCount() : 0,
                        snap.getAvgViews(),
                        "из базы Pulse · «" + niche + "»"));
                if (peers.size() >= 20) {
                    break;
                }
            }
        }

        if (peers.isEmpty()) {
            boolean apiOn = tgstatApiClient.isEnabled();
            StringBuilder why = new StringBuilder();
            if (!apiOn) {
                why.append("TGStat API не подключён (нет token в pulse.external.tgstat-token).\n");
            } else if (!useTgstat) {
                why.append("Глубокий поиск площадок через TGStat — в тарифах CONTENT и PRO.\n");
                why.append("Сейчас доступен только поиск по уже разобранным каналам в базе Pulse.\n");
            } else {
                why.append("По запросу «").append(searchQuery).append("» кандидатов нет.\n");
                why.append("Частые причины: на токене нет доступа к /channels/search (нужен платный Stat), ")
                        .append("квота, или ищем по названию канала вместо ниши.\n");
            }
            if (niche == null || niche.isBlank()) {
                why.append("\nУ канала не сохранена категория после разбора — ")
                        .append("подбор ищет по названию («").append(searchQuery).append("»), это почти всегда пусто.\n");
            }
            why.append("\nЧто сделать:\n");
            if (!useTgstat) {
                why.append("• возьми пакет CONTENT/PRO — откроется поиск TGStat по нише\n");
            } else {
                why.append("• возьми тариф TGStat API Stat и проверь, что token от Stat (не только веб)\n");
            }
            why.append("• разбери 1–2 канала своей тематики — появится category в базе\n");
            why.append("• или «⋯ Ещё» → проверка @площадки вручную");
            return MatchResult.empty(why.toString());
        }

        List<ScoredPlacement> scored = new ArrayList<>();
        int n = 0;
        for (PeerHint hint : peers.values()) {
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

        String label = niche != null ? niche : searchQuery;
        return new MatchResult(label, scored, null);
    }

    private static void addPeer(Map<String, PeerHint> peers, NicheComparison.Peer p, String reason) {
        if (p.username() == null || p.username().isBlank()) {
            return;
        }
        String u = p.username().toLowerCase(Locale.ROOT);
        peers.putIfAbsent(u, new PeerHint(p.title(), u, p.subscribers(), null, reason));
    }

    private String resolveNicheLabel(ChannelEntity owner) {
        if (owner.getCategory() != null && !owner.getCategory().isBlank()) {
            return owner.getCategory().trim();
        }
        return profileRepository.findByChannelIdOrderByAnalyzedAtDesc(owner.getId()).stream()
                .map(ChannelProfileSnapshotEntity::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** Строка для поиска: ниша; название канала — только если похоже на тему, не бренд. */
    private String resolveSearchQuery(ChannelEntity owner, String niche) {
        if (niche != null && !niche.isBlank()) {
            return niche;
        }
        String title = owner.getTitle() != null ? owner.getTitle() : "";
        String cleaned = NOISE.matcher(title).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        // Бренд продукта / слишком общее имя — не годится как ниша для TGStat search
        if (lower.contains("pulse") || lower.equals("ai") || cleaned.length() < 4) {
            return "бизнес";
        }
        String[] parts = cleaned.split(" ");
        StringBuilder q = new StringBuilder();
        for (String p : parts) {
            if (p.length() < 3) {
                continue;
            }
            String pl = p.toLowerCase(Locale.ROOT);
            if (pl.equals("pulse") || pl.equals("channel") || pl.equals("канал")) {
                continue;
            }
            if (q.length() > 0) {
                q.append(' ');
            }
            q.append(p);
            if (q.length() > 40) {
                break;
            }
        }
        if (q.length() >= 3) {
            return q.toString();
        }
        return owner.getUsername() != null ? owner.getUsername() : "telegram";
    }

    private record PeerHint(String title, String username, int subscribers, Integer avgViews, String reason) {
    }

    public record ScoredPlacement(
            AdPlacementEntity placement,
            String matchReason,
            int subscribersHint,
            int estimatedPriceRub
    ) {
    }

    public record MatchResult(String category, List<ScoredPlacement> placements, String emptyMessage) {
        static MatchResult empty(String msg) {
            return new MatchResult(null, List.of(), msg);
        }

        public boolean isEmpty() {
            return placements == null || placements.isEmpty();
        }
    }
}
