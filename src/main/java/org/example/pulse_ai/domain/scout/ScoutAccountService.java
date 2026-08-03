package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.repository.ScoutAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutAccountService {

    private static final Set<String> SENDER_TYPES = Set.of("OUTREACH", "SENDER");
    private static final Set<String> PARSER_TYPES = Set.of("OBSERVER", "PARSER");
    /** SpamBot чаще 4×/сутки только раздражает антиспам TG. */
    public static final int SPAMBOT_DAILY_MAX = 4;

    private final ScoutAccountRepository accountRepository;
    private final ScoutSessionGateway scoutGateway;
    private final ScoutActionLogService actionLogService;

    @Transactional(readOnly = true)
    public List<ScoutAccountEntity> listAll() {
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<ScoutAccountEntity> find(long id) {
        return accountRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ScoutAccountEntity> activeSenders() {
        return accountRepository.findAll().stream()
                .filter(a -> SENDER_TYPES.contains(a.getAccountType()))
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .filter(a -> a.getSentToday() < Math.max(a.getDailyLimit(), 1))
                .sorted((a, b) -> {
                    Instant la = a.getLastSentAt() != null ? a.getLastSentAt() : Instant.EPOCH;
                    Instant lb = b.getLastSentAt() != null ? b.getLastSentAt() : Instant.EPOCH;
                    return la.compareTo(lb);
                })
                .toList();
    }

    /** @deprecated use {@link #activeSenders()} */
    @Deprecated
    @Transactional(readOnly = true)
    public List<ScoutAccountEntity> activeOutreach() {
        return activeSenders();
    }

    @Transactional(readOnly = true)
    public Optional<ScoutAccountEntity> pickOutreachAccount() {
        return activeSenders().stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<ScoutAccountEntity> pickParserAccount() {
        return accountRepository.findAll().stream()
                .filter(a -> PARSER_TYPES.contains(a.getAccountType()))
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .findFirst();
    }

    @Transactional
    public void recordSend(long accountId, boolean success, String error) {
        accountRepository.findById(accountId).ifPresent(account -> {
            if (success) {
                account.setSentToday(account.getSentToday() + 1);
                account.setLastSentAt(Instant.now());
                account.setLastError(null);
                accountRepository.save(account);
                return;
            }
            account.setLastError(error);
            boolean flood = error != null && (error.contains("FLOOD") || error.contains("PEER_FLOOD")
                    || error.contains("BANNED") || error.toLowerCase().contains("spam"));
            if (flood) {
                account.setStatus("FLOOD_WAIT");
                accountRepository.save(account);
                // SpamBot имеет смысл только у писателей. PARSER/OBSERVER не пишут ЛС.
                if (isSenderType(account.getAccountType())) {
                    recoverFromFlood(account);
                }
            } else {
                accountRepository.save(account);
            }
        });
    }

    /**
     * Ротация прокси + /start @SpamBot.
     * Только SENDER/OUTREACH, максимум {@link #SPAMBOT_DAILY_MAX} раз в сутки.
     */
    public ScoutSessionGateway.SimpleResult recoverFromFlood(ScoutAccountEntity account) {
        return runSpamBotInternal(account, true);
    }

    /** Ручной /start @SpamBot из админки — с теми же гейтами, без обязательной ротации прокси. */
    public ScoutSessionGateway.SimpleResult runSpamBot(long accountId) {
        return accountRepository.findById(accountId)
                .map(a -> runSpamBotInternal(a, false))
                .orElse(ScoutSessionGateway.SimpleResult.failed("account not found"));
    }

    private ScoutSessionGateway.SimpleResult runSpamBotInternal(ScoutAccountEntity account, boolean rotateProxy) {
        if (!isSenderType(account.getAccountType())) {
            return ScoutSessionGateway.SimpleResult.failed(
                    "SpamBot только для SENDER/OUTREACH — парсеры/наблюдатели не пишут ЛС");
        }
        if (account.getSpambotToday() >= SPAMBOT_DAILY_MAX) {
            log.info("SpamBot skip #{}: daily limit {} reached", account.getId(), SPAMBOT_DAILY_MAX);
            actionLogService.log(account.getId(), null, "SPAMBOT", "SKIP",
                    "daily limit " + SPAMBOT_DAILY_MAX, "max 4/day");
            return ScoutSessionGateway.SimpleResult.failed(
                    "SpamBot уже " + account.getSpambotToday() + "/" + SPAMBOT_DAILY_MAX
                            + " сегодня — завтра или вручную после паузы");
        }
        try {
            if (rotateProxy) {
                ScoutSessionGateway.SimpleResult rotate = scoutGateway.rotateProxy(account.getId());
                actionLogService.log(account.getId(), null, "PROXY_ROTATE",
                        rotate.ok() ? "OK" : "FAIL", rotate.detail(), rotate.error());
            }
            ScoutSessionGateway.SimpleResult spam = scoutGateway.spamBotStart(account.getId());
            account.setSpambotToday(account.getSpambotToday() + 1);
            account.setLastSpambotAt(Instant.now());
            actionLogService.log(account.getId(), null, "SPAMBOT",
                    spam.ok() ? "OK" : "FAIL",
                    "try " + account.getSpambotToday() + "/" + SPAMBOT_DAILY_MAX
                            + (spam.detail() != null ? (" · " + spam.detail()) : ""),
                    spam.error());
            if (spam.ok()) {
                account.setStatus("ACTIVE");
                account.setLastError("recovered via SpamBot: " + spam.detail());
            }
            accountRepository.save(account);
            if (spam.ok()) {
                return ScoutSessionGateway.SimpleResult.ok(
                        "SpamBot " + account.getSpambotToday() + "/" + SPAMBOT_DAILY_MAX
                                + (spam.detail() != null ? (" · " + spam.detail()) : ""));
            }
            return ScoutSessionGateway.SimpleResult.failed(
                    (spam.error() != null ? spam.error() : "fail")
                            + " · " + account.getSpambotToday() + "/" + SPAMBOT_DAILY_MAX + " сегодня");
        } catch (Exception ex) {
            log.warn("SpamBot failed for account {}: {}", account.getId(), ex.getMessage());
            return ScoutSessionGateway.SimpleResult.failed(ex.getMessage());
        }
    }

    @Transactional
    public ScoutAccountEntity create(String label, String accountType, int dailyLimit) {
        ScoutAccountEntity entity = new ScoutAccountEntity();
        entity.setId(allocateId(accountType));
        entity.setLabel(label);
        entity.setAccountType(accountType);
        entity.setStatus("WARMING");
        entity.setDailyLimit(dailyLimit);
        entity.setSentToday(0);
        return accountRepository.save(entity);
    }

    @Transactional
    public ScoutAccountEntity save(ScoutAccountEntity account) {
        return accountRepository.save(account);
    }

    /**
     * Визуальные диапазоны:
     * PARSER/OBSERVER → 1–99 (одинарные/короткие), overflow 1000–1999
     * SENDER/OUTREACH → 100–999 (двойные+), overflow 2000–9999
     * Legacy #33 (sender) остаётся как есть.
     */
    public long allocateId(String accountType) {
        String t = accountType == null ? "" : accountType.trim().toUpperCase();
        boolean sender = SENDER_TYPES.contains(t);
        long primaryMin = sender ? 100L : 1L;
        long primaryMax = sender ? 999L : 99L;
        long overflowMin = sender ? 2000L : 1000L;
        long overflowMax = sender ? 9999L : 1999L;
        var used = accountRepository.findAll().stream()
                .map(ScoutAccountEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (long i = primaryMin; i <= primaryMax; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        for (long i = overflowMin; i <= overflowMax; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        throw new IllegalStateException(sender
                ? "Закончились ID для SENDER (100–999 и 2000–9999)"
                : "Закончились ID для PARSER/OBSERVER (1–99 и 1000–1999)");
    }

    public static boolean isSenderType(String accountType) {
        return accountType != null && SENDER_TYPES.contains(accountType.trim().toUpperCase());
    }

    public static boolean isParserType(String accountType) {
        return accountType != null && PARSER_TYPES.contains(accountType.trim().toUpperCase());
    }

    @Transactional
    public void setStatus(long accountId, String status) {
        accountRepository.findById(accountId).ifPresent(account -> {
            account.setStatus(status);
            accountRepository.save(account);
        });
    }

    @Transactional
    public void setDailyLimit(long accountId, int limit) {
        accountRepository.findById(accountId).ifPresent(account -> {
            account.setDailyLimit(Math.max(0, Math.min(limit, 40)));
            accountRepository.save(account);
        });
    }

    @Transactional
    public void setAccountType(long accountId, String type) {
        accountRepository.findById(accountId).ifPresent(account -> {
            account.setAccountType(type);
            accountRepository.save(account);
        });
    }

    @Transactional
    public void pause(long accountId) {
        setStatus(accountId, "PAUSED");
    }

    @Transactional
    public void resume(long accountId) {
        setStatus(accountId, "ACTIVE");
    }

    /** Ключ убит Telegram — в работу не берём, но карточку и данные покупки храним. */
    @Transactional
    public boolean markBurned(long accountId, String error) {
        var found = accountRepository.findById(accountId);
        if (found.isEmpty()) {
            return false;
        }
        ScoutAccountEntity account = found.get();
        boolean changed = !"BURNED".equals(account.getStatus());
        account.setStatus("BURNED");
        if (error != null && !error.isBlank()) {
            account.setLastError(error.length() > 500 ? error.substring(0, 500) : error);
        }
        accountRepository.save(account);
        if (changed) {
            log.warn("Scout #{} ({}) marked BURNED: {}", accountId, account.getLabel(), error);
        }
        return changed;
    }

    @Transactional
    public boolean delete(long accountId) {
        if (!accountRepository.existsById(accountId)) {
            return false;
        }
        accountRepository.deleteById(accountId);
        return true;
    }

    @Transactional
    public void resetDailyCounters() {
        for (ScoutAccountEntity account : accountRepository.findAll()) {
            account.setSentToday(0);
            account.setSpambotToday(0);
            accountRepository.save(account);
        }
    }
}
