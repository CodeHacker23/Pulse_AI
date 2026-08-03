package org.example.pulse_ai.stats.external;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.config.PulseExternalProperties;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.request.RequestType;
import org.example.pulse_ai.persistence.repository.PaymentRepository;
import org.springframework.stereotype.Service;

/**
 * TGStat — платный слой: free/START без API, CONTENT+ и локальная разработка — с API.
 */
@Service
@RequiredArgsConstructor
public class TgstatAccessService {

    private final PulseExternalProperties externalProperties;
    private final PulseBillingProperties billingProperties;
    private final EntitlementService entitlementService;
    private final PaymentRepository paymentRepository;

    public boolean tokenConfigured() {
        return externalProperties.isTgstatEnabled();
    }

    /**
     * Разбор канала: FREE всегда scrape; PAID + CONTENT+ → TGStat.
     * При billing.enabled=false (локалка) — TGStat если есть token.
     */
    public boolean forAnalysis(long userId, RequestType type) {
        if (!tokenConfigured()) {
            return false;
        }
        if (!billingProperties.isEnabled() || !externalProperties.isTgstatPaidOnly()) {
            return true;
        }
        if (type == RequestType.FREE) {
            return false;
        }
        return isContentPlusUser(userId);
    }

    /** Подбор площадок / searchPeers — только CONTENT+. */
    public boolean forPlacementSearch(long userId) {
        if (!tokenConfigured()) {
            return false;
        }
        if (!billingProperties.isEnabled() || !externalProperties.isTgstatPaidOnly()) {
            return true;
        }
        return isContentPlusUser(userId);
    }

    public boolean isContentPlusUser(long userId) {
        return entitlementService.hasAccess(userId, PerkType.MANAGER)
                || entitlementService.hasAccess(userId, PerkType.DIGEST)
                || entitlementService.hasAccess(userId, PerkType.COMPETITOR)
                || entitlementService.hasAccess(userId, PerkType.COMMENTS)
                || entitlementService.hasAccess(userId, PerkType.SELLING)
                || entitlementService.hasAccess(userId, PerkType.LIBRARY)
                || entitlementService.hasAccess(userId, PerkType.ANTISPAM)
                || paymentRepository.hasCompletedContentPlusPurchase(userId);
    }
}
