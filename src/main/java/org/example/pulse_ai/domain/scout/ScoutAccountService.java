package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.repository.ScoutAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
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
    /** После FLOOD не ACTIVE, пока не истечёт карантин (минуты). */
    public static final int FLOOD_QUARANTINE_MIN = 120;

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
            boolean flood = error != null && (error.toUpperCase(Locale.ROOT).contains("FLOOD")
                    || error.toUpperCase(Locale.ROOT).contains("PEER_FLOOD")
                    || error.toUpperCase(Locale.ROOT).contains("BANNED")
                    || error.toLowerCase(Locale.ROOT).contains("spam"));
            if (flood) {
                enterQuarantine(account, FLOOD_QUARANTINE_MIN, error);
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
     * Карантин после FLOOD: статус {@code FLOOD_WAIT} до {@code quarantineUntil}.
     * Join/рассылка не берут акк; «Старт» в ACTIVE заблокирован до конца таймера.
     */
    @Transactional
    public void enterQuarantine(long accountId, int minutes, String reason) {
        accountRepository.findById(accountId).ifPresent(a -> enterQuarantine(a, minutes, reason));
    }

    private void enterQuarantine(ScoutAccountEntity account, int minutes, String reason) {
        Instant until = Instant.now().plus(Math.max(1, minutes), ChronoUnit.MINUTES);
        Instant existing = account.getQuarantineUntil();
        if (existing != null && existing.isAfter(until)) {
            until = existing;
        }
        account.setStatus("FLOOD_WAIT");
        account.setQuarantineUntil(until);
        if (reason != null && !reason.isBlank()) {
            String msg = "карантин до " + until + " · " + reason;
            account.setLastError(msg.length() > 500 ? msg.substring(0, 500) : msg);
        }
        accountRepository.save(account);
        actionLogService.log(account.getId(), null, "QUARANTINE", "OK",
                minutes + "м · " + until, reason);
        log.info("Scout #{} → FLOOD_WAIT until {}", account.getId(), until);
    }

    public boolean isInQuarantine(ScoutAccountEntity account) {
        if (account == null) {
            return false;
        }
        Instant until = account.getQuarantineUntil();
        return until != null && until.isAfter(Instant.now());
    }

    /** Снять истёкший карантин → ACTIVE (или оставить BURNED/PAUSED). */
    @Transactional
    public int releaseExpiredQuarantines() {
        Instant now = Instant.now();
        int n = 0;
        for (ScoutAccountEntity account : accountRepository.findAll()) {
            Instant until = account.getQuarantineUntil();
            if (until == null || until.isAfter(now)) {
                continue;
            }
            String st = String.valueOf(account.getStatus()).toUpperCase(Locale.ROOT);
            if ("BURNED".equals(st) || "BANNED".equals(st) || "PAUSED".equals(st)) {
                account.setQuarantineUntil(null);
                accountRepository.save(account);
                continue;
            }
            if ("FLOOD_WAIT".equals(st) || "WARMING".equals(st)) {
                account.setStatus("ACTIVE");
                account.setQuarantineUntil(null);
                account.setLastError(null);
                accountRepository.save(account);
                actionLogService.log(account.getId(), null, "QUARANTINE", "DONE", "released → ACTIVE", null);
                n++;
            } else {
                account.setQuarantineUntil(null);
                accountRepository.save(account);
            }
        }
        return n;
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
                // Не выходим из карантина раньше времени — SpamBot только диагностирует.
                if (!isInQuarantine(account)) {
                    account.setStatus("ACTIVE");
                    account.setQuarantineUntil(null);
                    account.setLastError("recovered via SpamBot: " + spam.detail());
                } else {
                    account.setLastError("SpamBot ok, карантин до " + account.getQuarantineUntil());
                }
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

    /**
     * @return null если ок, иначе причина отказа
     */
    @Transactional
    public String resume(long accountId) {
        var found = accountRepository.findById(accountId);
        if (found.isEmpty()) {
            return "аккаунт не найден";
        }
        ScoutAccountEntity account = found.get();
        if (isInQuarantine(account)) {
            return "Карантин после FLOOD до " + account.getQuarantineUntil()
                    + " — нельзя ACTIVE раньше";
        }
        account.setStatus("ACTIVE");
        account.setQuarantineUntil(null);
        accountRepository.save(account);
        return null;
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
