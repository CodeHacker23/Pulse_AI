package org.example.pulse_ai.domain.entitlement;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.config.PulseOutreachProperties;
import org.example.pulse_ai.domain.payment.PackageKind;
import org.example.pulse_ai.persistence.entity.OutreachMonthlyUsageEntity;
import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.example.pulse_ai.persistence.entity.UserEntitlementEntity;
import org.example.pulse_ai.persistence.repository.OutreachMonthlyUsageRepository;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.persistence.repository.UserEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Квоты Pulse Ассистент: ЛС/мес по тарифу + допы, парсинг своих ссылок.
 */
@Service
@RequiredArgsConstructor
public class AssistantQuotaService {

    public static final String PERK_OUTREACH_TOPUP = "OUTREACH_TOPUP";
    public static final String PERK_PARSE_OWN = "PARSE_OWN";
    public static final String PERK_FIND_AUDIENCE = "FIND_AUDIENCE";

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final List<String> TIER_CODES = List.of("ASSIST_PRO", "ASSIST_PLUS", "ASSIST");

    private final PulseBillingProperties billingProperties;
    private final PulseOutreachProperties outreachProperties;
    private final UserEntitlementRepository entitlementRepository;
    private final PackageRepository packageRepository;
    private final OutreachMonthlyUsageRepository usageRepository;
    private final EntitlementService entitlementService;

    @Transactional(readOnly = true)
    public boolean hasAssistant(Long userId) {
        return entitlementService.hasAccess(userId, PerkType.MANAGER);
    }

    @Transactional(readOnly = true)
    public Optional<PackageEntity> activeTierPackage(Long userId) {
        Instant now = Instant.now();
        for (String code : TIER_CODES) {
            if (!entitlementRepository.findActive(userId, code, now).isEmpty()) {
                return packageRepository.findAll().stream()
                        .filter(p -> code.equals(p.getCode()))
                        .findFirst();
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public DmQuotaSnapshot dmQuota(Long userId) {
        int base = resolveBaseDmQuota(userId);
        int used = monthlyUsed(userId);
        int topup = sumUses(userId, PERK_OUTREACH_TOPUP);
        int fromBase = Math.max(0, base - used);
        int remaining = fromBase + topup;
        return new DmQuotaSnapshot(base, used, topup, remaining, activeTierPackage(userId).map(PackageEntity::getName).orElse(null));
    }

    @Transactional(readOnly = true)
    public ParseQuotaSnapshot parseQuota(Long userId) {
        if (!billingProperties.isEnabled()) {
            int open = 99;
            return new ParseQuotaSnapshot(open, open);
        }
        int remaining = sumUses(userId, PERK_PARSE_OWN);
        int granted = activeTierPackage(userId).map(PackageEntity::getParseQuota).orElse(0);
        return new ParseQuotaSnapshot(granted, remaining);
    }

    @Transactional(readOnly = true)
    public boolean canFindAudience(Long userId) {
        if (!billingProperties.isEnabled()) {
            return true;
        }
        return !entitlementRepository.findActive(userId, PERK_FIND_AUDIENCE, Instant.now()).isEmpty()
                || activeTierPackage(userId).map(PackageEntity::isIncludesFindAudience).orElse(false);
    }

    /** Списать 1 доп ЛС (когда месячная квота тарифа уже выбрана). */
    @Transactional
    public boolean consumeTopupDm(Long userId) {
        if (!billingProperties.isEnabled()) {
            return true;
        }
        return entitlementService.tryConsumeRaw(userId, PERK_OUTREACH_TOPUP);
    }

    @Transactional
    public boolean tryConsumeParse(Long userId) {
        if (!billingProperties.isEnabled()) {
            return true;
        }
        return entitlementService.tryConsumeRaw(userId, PERK_PARSE_OWN);
    }

    private int resolveBaseDmQuota(Long userId) {
        if (!billingProperties.isEnabled()) {
            return outreachProperties.getMonthlySendLimit();
        }
        return activeTierPackage(userId)
                .map(PackageEntity::getDmQuota)
                .filter(q -> q > 0)
                .orElse(0);
    }

    private int monthlyUsed(Long userId) {
        OutreachMonthlyUsageEntity.Pk pk = new OutreachMonthlyUsageEntity.Pk();
        pk.setUserId(userId);
        pk.setMonthKey(monthKey());
        return usageRepository.findById(pk).map(OutreachMonthlyUsageEntity::getSentCount).orElse(0);
    }

    private int sumUses(Long userId, String perkCode) {
        return entitlementRepository.findActive(userId, perkCode, Instant.now()).stream()
                .map(UserEntitlementEntity::getUsesRemaining)
                .filter(u -> u != null && u > 0)
                .mapToInt(Integer::intValue)
                .sum();
    }

    public static String monthKey() {
        return DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.now().atZone(MSK));
    }

    public record DmQuotaSnapshot(int base, int used, int topupRemaining, int remaining, String tierName) {
        public String counterLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("ЛС: <b>").append(remaining).append("</b> ост. · квота ").append(base);
            if (topupRemaining > 0) {
                sb.append(" + допы ").append(topupRemaining);
            }
            if (tierName != null && !tierName.isBlank()) {
                sb.append(" · ").append(tierName);
            }
            return sb.toString();
        }
    }

    public record ParseQuotaSnapshot(int granted, int remaining) {
    }

    /** Лучший активный ASSIST-пакет по dm_quota (для апгрейда). */
    @Transactional(readOnly = true)
    public Optional<PackageEntity> bestAssistantPackageAvailable() {
        return packageRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .filter(p -> PackageKind.from(p.getKind()) == PackageKind.ASSISTANT)
                .max(Comparator.comparingInt(PackageEntity::getDmQuota));
    }
}
