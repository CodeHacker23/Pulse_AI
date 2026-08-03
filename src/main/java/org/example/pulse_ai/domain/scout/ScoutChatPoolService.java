package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.entity.ScoutChatMembershipEntity;
import org.example.pulse_ai.persistence.entity.ScoutTargetChatEntity;
import org.example.pulse_ai.persistence.repository.ScoutChatMembershipRepository;
import org.example.pulse_ai.persistence.repository.ScoutTargetChatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Единый пул групп/каналов для PARSER/OBSERVER.
 * Список не привязан к одному скауту: сгорел акк → новый забирает очередь сам.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutChatPoolService {

    public static final int JOIN_INTERVAL_SEC = 30;
    public static final int MAX_JOIN_ATTEMPTS = 5;

    private final ScoutTargetChatRepository chatRepository;
    private final ScoutChatMembershipRepository membershipRepository;
    private final ScoutAccountService accountService;
    private final ScoutSessionGateway scoutGateway;
    private final ScoutActionLogService actionLogService;

    @Transactional
    public EnqueueResult enqueueLinks(String rawText, Long preferredAccountId) {
        List<String> links = ScoutChatImportService.parseLinks(rawText);
        if (links.isEmpty()) {
            return EnqueueResult.empty("Не нашёл ссылок. Формат: t.me/group, @username — по одной на строку.");
        }

        List<ScoutAccountEntity> watchers = accountService.listAll().stream()
                .filter(a -> ScoutAccountService.isParserType(a.getAccountType()))
                .filter(a -> {
                    String s = String.valueOf(a.getStatus()).toUpperCase(Locale.ROOT);
                    return "ACTIVE".equals(s) || "WARMING".equals(s);
                })
                .toList();
        if (preferredAccountId != null) {
            watchers = watchers.stream()
                    .filter(a -> preferredAccountId.equals(a.getId()))
                    .toList();
            if (watchers.isEmpty()) {
                return EnqueueResult.empty("Скаут #" + preferredAccountId + " не найден / не PARSER|OBSERVER");
            }
        }
        if (watchers.isEmpty()) {
            // всё равно кладём в пул — memberships появятся, когда появится живой парсер
            watchers = List.of();
        }

        int addedChats = 0;
        int already = 0;
        int memberships = 0;
        List<String> lines = new ArrayList<>();
        for (String link : links) {
            String norm = normalize(link);
            Optional<ScoutTargetChatEntity> existing = chatRepository.findByNormalizedLink(norm);
            ScoutTargetChatEntity chat;
            if (existing.isPresent()) {
                chat = existing.get();
                already++;
                lines.add(link + " → уже в пуле #" + chat.getId());
            } else {
                chat = new ScoutTargetChatEntity();
                chat.setLink(link);
                chat.setNormalizedLink(norm);
                chat.setKind(guessKind(link));
                chat.setStatus("ACTIVE");
                chat = chatRepository.save(chat);
                addedChats++;
                lines.add(link + " → добавлен #" + chat.getId());
            }
            for (ScoutAccountEntity acc : watchers) {
                if (membershipRepository.findByChatIdAndScoutAccountId(chat.getId(), acc.getId()).isEmpty()) {
                    ScoutChatMembershipEntity m = new ScoutChatMembershipEntity();
                    m.setChatId(chat.getId());
                    m.setScoutAccountId(acc.getId());
                    m.setStatus("PENDING");
                    m.setNextAttemptAt(Instant.now());
                    membershipRepository.save(m);
                    memberships++;
                }
            }
        }
        String detail = "В пул: +" + addedChats + " новых, " + already + " уже были · очередь join +"
                + memberships + " · интервал " + JOIN_INTERVAL_SEC + "с";
        return new EnqueueResult(true, addedChats, already, memberships, links.size(), lines, detail, null);
    }

    /**
     * Новый PARSER/OBSERVER: подписать на все ACTIVE чаты из пула (без немедленного join).
     */
    @Transactional
    public int enrollAccount(long accountId) {
        ScoutAccountEntity acc = accountService.find(accountId).orElse(null);
        if (acc == null || !ScoutAccountService.isParserType(acc.getAccountType())) {
            return 0;
        }
        int created = 0;
        for (ScoutTargetChatEntity chat : chatRepository.findByStatusOrderByPriorityDescIdAsc("ACTIVE")) {
            if (ensurePending(chat.getId(), accountId)) {
                created++;
            }
        }
        return created;
    }

    /**
     * Отказоустойчивость: сгоревший скаут отдаёт свою очередь живому.
     * Список групп в пуле не трогаем — теряется только «я уже был внутри TG»,
     * новый акк заново join'ит по тем же ссылкам (медленно).
     */
    @Transactional
    public FailoverResult handoff(long fromAccountId, Long toAccountIdOrNull) {
        ScoutAccountEntity from = accountService.find(fromAccountId).orElse(null);
        if (from == null) {
            return FailoverResult.fail("источник #" + fromAccountId + " не найден");
        }
        int retired = retireAccount(fromAccountId, "handoff from burned/failed scout");

        ScoutAccountEntity to = null;
        if (toAccountIdOrNull != null) {
            to = accountService.find(toAccountIdOrNull).orElse(null);
            if (to == null) {
                return FailoverResult.fail("получатель #" + toAccountIdOrNull + " не найден");
            }
            if (!ScoutAccountService.isParserType(to.getAccountType())) {
                return FailoverResult.fail("получатель должен быть PARSER/OBSERVER");
            }
            String st = String.valueOf(to.getStatus()).toUpperCase(Locale.ROOT);
            if ("BURNED".equals(st) || "BANNED".equals(st)) {
                return FailoverResult.fail("получатель тоже мёртв (" + st + ")");
            }
        } else {
            to = pickHealthyWatcher(fromAccountId).orElse(null);
        }
        if (to == null) {
            actionLogService.log(fromAccountId, null, "FAILOVER", "SKIP",
                    "retired=" + retired + " · нет живого PARSER/OBSERVER", null);
            return new FailoverResult(true, fromAccountId, null, retired, 0,
                    "Очередь #" + fromAccountId + " снята (" + retired + "). "
                            + "Живого наблюдателя нет — пул групп сохранён, "
                            + "когда добавишь нового — жми «В пул групп» / handoff.");
        }

        // 1) чаты, где донор реально стоял в очереди / был JOINED
        int fromChats = 0;
        for (ScoutChatMembershipEntity old : membershipRepository.findByScoutAccountId(fromAccountId)) {
            if (ensurePending(old.getChatId(), to.getId())) {
                fromChats++;
            }
        }
        // 2) плюс весь ACTIVE-пул (на случай если донор ещё не успел получить memberships)
        int poolExtra = 0;
        for (ScoutTargetChatEntity chat : chatRepository.findByStatusOrderByPriorityDescIdAsc("ACTIVE")) {
            if (ensurePending(chat.getId(), to.getId())) {
                poolExtra++;
            }
        }
        int enrolled = fromChats + poolExtra;
        String detail = "Failover #" + fromAccountId + " → #" + to.getId()
                + " (" + to.getLabel() + "): снято " + retired
                + ", в очередь +" + enrolled + " (из донора " + fromChats + " + пул " + poolExtra + ")";
        actionLogService.log(to.getId(), null, "FAILOVER", "OK", detail, null);
        actionLogService.log(fromAccountId, null, "FAILOVER", "OK", detail, null);
        log.info(detail);
        return new FailoverResult(true, fromAccountId, to.getId(), retired, enrolled, detail);
    }

    /** Снять живую очередь с акка (PENDING/JOINING → FAILED), JOINED оставляем как историю. */
    @Transactional
    public int retireAccount(long accountId, String reason) {
        int n = 0;
        String why = reason != null ? reason : "retired";
        for (ScoutChatMembershipEntity m : membershipRepository.findByScoutAccountId(accountId)) {
            String s = String.valueOf(m.getStatus()).toUpperCase(Locale.ROOT);
            if ("PENDING".equals(s) || "JOINING".equals(s) || "FAILED".equals(s)) {
                m.setStatus("FAILED");
                m.setLastError(why.length() > 500 ? why.substring(0, 500) : why);
                m.setNextAttemptAt(null);
                membershipRepository.save(m);
                n++;
            }
        }
        return n;
    }

    private boolean ensurePending(long chatId, long accountId) {
        var existing = membershipRepository.findByChatIdAndScoutAccountId(chatId, accountId);
        if (existing.isPresent()) {
            ScoutChatMembershipEntity m = existing.get();
            String s = String.valueOf(m.getStatus()).toUpperCase(Locale.ROOT);
            if ("JOINED".equals(s) || "PENDING".equals(s) || "JOINING".equals(s)) {
                return false;
            }
            // FAILED → снова в работу
            m.setStatus("PENDING");
            m.setAttempts(0);
            m.setLastError(null);
            m.setNextAttemptAt(Instant.now());
            membershipRepository.save(m);
            return true;
        }
        ScoutChatMembershipEntity m = new ScoutChatMembershipEntity();
        m.setChatId(chatId);
        m.setScoutAccountId(accountId);
        m.setStatus("PENDING");
        m.setNextAttemptAt(Instant.now());
        membershipRepository.save(m);
        return true;
    }

    private Optional<ScoutAccountEntity> pickHealthyWatcher(long excludeId) {
        return accountService.listAll().stream()
                .filter(a -> ScoutAccountService.isParserType(a.getAccountType()))
                .filter(a -> !a.getId().equals(excludeId))
                .filter(a -> {
                    String s = String.valueOf(a.getStatus()).toUpperCase(Locale.ROOT);
                    return "ACTIVE".equals(s) || "WARMING".equals(s);
                })
                .findFirst();
    }

    /** Один join за тик — чтобы не словить бан на массовом вступлении. */
    @Transactional
    public Optional<String> processOneJoin() {
        List<ScoutChatMembershipEntity> queue =
                membershipRepository.findReadyQueue(Instant.now(), MAX_JOIN_ATTEMPTS);
        for (ScoutChatMembershipEntity m : queue) {
            ScoutAccountEntity acc = accountService.find(m.getScoutAccountId()).orElse(null);
            if (acc == null || !ScoutAccountService.isParserType(acc.getAccountType())) {
                m.setStatus("FAILED");
                m.setLastError("account gone / not parser");
                membershipRepository.save(m);
                continue;
            }
            String st = String.valueOf(acc.getStatus()).toUpperCase(Locale.ROOT);
            if (!"ACTIVE".equals(st) && !"WARMING".equals(st)) {
                continue; // подождём — акк на паузе / сгорел
            }
            if ("BURNED".equals(st) || "BANNED".equals(st)) {
                m.setStatus("FAILED");
                m.setLastError("account " + st);
                membershipRepository.save(m);
                continue;
            }
            ScoutTargetChatEntity chat = chatRepository.findById(m.getChatId()).orElse(null);
            if (chat == null || !"ACTIVE".equals(chat.getStatus())) {
                m.setStatus("FAILED");
                m.setLastError("chat inactive");
                membershipRepository.save(m);
                continue;
            }

            m.setStatus("JOINING");
            m.setAttempts(m.getAttempts() + 1);
            membershipRepository.save(m);

            ScoutSessionGateway.JoinResult r = scoutGateway.joinChat(acc.getId(), chat.getLink());
            if (r.ok()) {
                m.setStatus("JOINED");
                m.setJoinedAt(Instant.now());
                m.setLastError(null);
                m.setNextAttemptAt(null);
                if (r.title() != null && !r.title().isBlank()) {
                    chat.setTitle(r.title());
                    chatRepository.save(chat);
                }
                membershipRepository.save(m);
                actionLogService.log(acc.getId(), null, "CHAT_JOIN", "OK",
                        chat.getLink() + (r.title() != null ? (" · " + r.title()) : ""), null);
                return Optional.of("acc#" + acc.getId() + " joined " + chat.getLink());
            }

            String err = r.error() != null ? r.error() : "join failed";
            m.setLastError(err.length() > 500 ? err.substring(0, 500) : err);
            boolean flood = err.toUpperCase(Locale.ROOT).contains("FLOOD")
                    || err.toUpperCase(Locale.ROOT).contains("WAIT");
            if (flood) {
                m.setStatus("PENDING");
                m.setNextAttemptAt(Instant.now().plus(15, ChronoUnit.MINUTES));
            } else if (m.getAttempts() >= MAX_JOIN_ATTEMPTS) {
                m.setStatus("FAILED");
            } else {
                m.setStatus("PENDING");
                m.setNextAttemptAt(Instant.now().plus(JOIN_INTERVAL_SEC * 2L, ChronoUnit.SECONDS));
            }
            membershipRepository.save(m);
            actionLogService.log(acc.getId(), null, "CHAT_JOIN", "FAIL", chat.getLink(), err);
            return Optional.of("acc#" + acc.getId() + " fail " + chat.getLink() + ": " + err);
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        return Map.of(
                "chatsActive", chatRepository.countByStatus("ACTIVE"),
                "chatsTotal", chatRepository.count(),
                "pending", membershipRepository.countByStatus("PENDING"),
                "joined", membershipRepository.countByStatus("JOINED"),
                "failed", membershipRepository.countByStatus("FAILED"),
                "joinIntervalSec", JOIN_INTERVAL_SEC
        );
    }

    @Transactional(readOnly = true)
    public List<ScoutTargetChatEntity> listChats() {
        return chatRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ScoutChatMembershipEntity> recentMemberships() {
        return membershipRepository.findTop30ByOrderByIdDesc();
    }

    public static String normalize(String link) {
        String t = link.trim().toLowerCase(Locale.ROOT);
        t = t.replace("https://", "").replace("http://", "");
        t = t.replace("telegram.me/", "t.me/");
        if (t.startsWith("@")) {
            return t;
        }
        if (t.startsWith("t.me/")) {
            String path = t.substring("t.me/".length());
            if (path.startsWith("+") || path.startsWith("joinchat/")) {
                return "t.me/" + path;
            }
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            return "@" + path.replace("/", "");
        }
        return t;
    }

    private static String guessKind(String link) {
        String n = normalize(link);
        if (n.contains("joinchat") || n.contains("t.me/+")) {
            return "GROUP";
        }
        return "GROUP";
    }

    public record EnqueueResult(
            boolean ok,
            int addedChats,
            int alreadyInPool,
            int membershipsCreated,
            int totalLinks,
            List<String> lines,
            String detail,
            String error
    ) {
        public static EnqueueResult empty(String error) {
            return new EnqueueResult(false, 0, 0, 0, 0, List.of(), null, error);
        }
    }

    public record FailoverResult(
            boolean ok,
            Long fromAccountId,
            Long toAccountId,
            int retired,
            int enrolled,
            String detail
    ) {
        public static FailoverResult fail(String detail) {
            return new FailoverResult(false, null, null, 0, 0, detail);
        }
    }
}
