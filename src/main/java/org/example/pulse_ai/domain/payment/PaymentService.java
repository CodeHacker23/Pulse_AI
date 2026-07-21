package org.example.pulse_ai.domain.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.persistence.entity.BalanceTransactionEntity;
import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.example.pulse_ai.persistence.entity.PaymentEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.BalanceTransactionRepository;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.persistence.repository.PaymentRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String PAYLOAD_PREFIX = "pulse:pay:";
    public static final String PROVIDER_STARS = "telegram_stars";

    private final PaymentRepository paymentRepository;
    private final PackageRepository packageRepository;
    private final UserRepository userRepository;
    private final BalanceTransactionRepository balanceTransactionRepository;
    private final EntitlementService entitlementService;

    @Transactional
    public PaymentEntity createStarsPayment(UserEntity user, short packageId) {
        PackageEntity pack = packageRepository.findById(packageId)
                .filter(PackageEntity::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Пакет не найден"));
        if (pack.getStarsAmount() == null || pack.getStarsAmount() <= 0) {
            throw new IllegalStateException("Для пакета не настроена цена в Stars");
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setUserId(user.getId());
        payment.setPackageId(pack.getId());
        payment.setProvider(PROVIDER_STARS);
        payment.setAmountRub(pack.getPriceRub());
        payment.setStatus(PaymentStatus.PENDING);
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentEntity> findPayment(long paymentId) {
        return paymentRepository.findById(paymentId);
    }

    public boolean validatePayload(String payload, long telegramUserId) {
        Long paymentId = parsePaymentId(payload);
        if (paymentId == null) {
            return false;
        }
        return paymentRepository.findById(paymentId)
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .flatMap(p -> userRepository.findById(p.getUserId()))
                .map(user -> user.getTelegramId().equals(telegramUserId))
                .orElse(false);
    }

    @Transactional
    public Optional<PaymentEntity> completeStarsPayment(String payload, String telegramPaymentChargeId, int totalAmount) {
        Long paymentId = parsePaymentId(payload);
        if (paymentId == null) {
            return Optional.empty();
        }
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
            return Optional.empty();
        }
        if (paymentRepository.findByExternalId(telegramPaymentChargeId).isPresent()) {
            log.warn("Duplicate Stars payment {}", telegramPaymentChargeId);
            return paymentRepository.findByExternalId(telegramPaymentChargeId);
        }

        PackageEntity pack = packageRepository.findById(payment.getPackageId()).orElse(null);
        if (pack == null) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return Optional.empty();
        }
        if (pack.getStarsAmount() != null && totalAmount < pack.getStarsAmount()) {
            log.warn("Stars amount mismatch: expected {}, got {}", pack.getStarsAmount(), totalAmount);
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return Optional.empty();
        }

        UserEntity user = userRepository.findById(payment.getUserId()).orElse(null);
        if (user == null) {
            return Optional.empty();
        }

        int credited = pack.getRequestCount();
        user.setBalance(user.getBalance() + credited);
        userRepository.save(user);

        recordBalance(user.getId(), credited, user.getBalance(), "stars_purchase", payment.getId());

        payment.setExternalId(telegramPaymentChargeId);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setRequestsCredited(credited);
        payment.setPerksRemainingToPick(pack.getPerkChoicesCount());
        payment.setCompletedAt(Instant.now());
        paymentRepository.save(payment);

        if (pack.isIncludesPriority()) {
            entitlementService.grantPriority(user.getId(), payment.getId());
        }

        log.info("Stars payment completed: user={}, package={}, +{} requests", user.getId(), pack.getCode(), credited);
        return Optional.of(payment);
    }

    @Transactional
    public void recordBalance(long userId, int delta, int balanceAfter, String reason, Long referenceId) {
        BalanceTransactionEntity tx = new BalanceTransactionEntity();
        tx.setUserId(userId);
        tx.setDelta(delta);
        tx.setBalanceAfter(balanceAfter);
        tx.setReason(reason);
        tx.setReferenceId(referenceId);
        balanceTransactionRepository.save(tx);
    }

    public static String payloadFor(long paymentId) {
        return PAYLOAD_PREFIX + paymentId;
    }

    public static Long parsePaymentId(String payload) {
        if (payload == null || !payload.startsWith(PAYLOAD_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(payload.substring(PAYLOAD_PREFIX.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
