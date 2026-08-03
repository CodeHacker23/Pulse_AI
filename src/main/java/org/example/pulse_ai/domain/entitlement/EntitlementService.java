package org.example.pulse_ai.domain.entitlement;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.persistence.entity.UserEntitlementEntity;
import org.example.pulse_ai.persistence.repository.UserEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final UserEntitlementRepository entitlementRepository;
    private final PulseBillingProperties billingProperties;

    /** В тестовом режиме (billing off) — доступ ко всем фичам для проверки. */
    public boolean hasAccess(Long userId, PerkType perk) {
        if (!billingProperties.isEnabled()) {
            return true;
        }
        return !findActive(userId, perk).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<UserEntitlementEntity> findActive(Long userId, PerkType perk) {
        return entitlementRepository.findActive(userId, perk.code(), Instant.now());
    }

    @Transactional
    public UserEntitlementEntity grant(Long userId, PerkType perk, Long sourcePaymentId) {
        return grantRaw(userId, perk.code(), perk.defaultUses(), perk.defaultDays(), sourcePaymentId);
    }

    @Transactional
    public UserEntitlementEntity grantRaw(
            Long userId,
            String perkCode,
            Integer usesRemaining,
            Integer daysValid,
            Long sourcePaymentId
    ) {
        UserEntitlementEntity entity = new UserEntitlementEntity();
        entity.setUserId(userId);
        entity.setPerkCode(perkCode);
        entity.setUsesRemaining(usesRemaining);
        entity.setSourcePaymentId(sourcePaymentId);
        if (daysValid != null) {
            entity.setExpiresAt(Instant.now().plus(daysValid, ChronoUnit.DAYS));
        }
        return entitlementRepository.save(entity);
    }

    @Transactional
    public boolean tryConsume(Long userId, PerkType perk) {
        return tryConsumeRaw(userId, perk.code());
    }

    @Transactional
    public boolean tryConsumeRaw(Long userId, String perkCode) {
        if (!billingProperties.isEnabled()) {
            return true;
        }
        List<UserEntitlementEntity> active = entitlementRepository.findActive(userId, perkCode, Instant.now());
        if (active.isEmpty()) {
            return false;
        }
        UserEntitlementEntity ent = active.get(0);
        if (ent.getUsesRemaining() == null) {
            return true;
        }
        if (ent.getUsesRemaining() <= 0) {
            return false;
        }
        ent.setUsesRemaining(ent.getUsesRemaining() - 1);
        entitlementRepository.save(ent);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasPriorityQueue(Long userId) {
        if (!billingProperties.isEnabled()) {
            return false;
        }
        return !entitlementRepository.findActive(userId, "PRIORITY", Instant.now()).isEmpty();
    }

    @Transactional
    public void grantPriority(Long userId, Long sourcePaymentId) {
        UserEntitlementEntity entity = new UserEntitlementEntity();
        entity.setUserId(userId);
        entity.setPerkCode("PRIORITY");
        entity.setUsesRemaining(null);
        entity.setExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS));
        entity.setSourcePaymentId(sourcePaymentId);
        entitlementRepository.save(entity);
    }
}
