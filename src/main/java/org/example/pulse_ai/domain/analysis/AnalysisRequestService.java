package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.request.RequestStatus;
import org.example.pulse_ai.domain.request.RequestType;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnalysisRequestService {

    private static final Set<RequestStatus> ACTIVE_STATUSES = EnumSet.of(
            RequestStatus.PENDING,
            RequestStatus.COLLECTING_STATS,
            RequestStatus.ANALYZING,
            RequestStatus.GENERATING_IDEAS,
            RequestStatus.GENERATING_POSTS
    );

    private final AnalysisRequestRepository requestRepository;
    private final UserService userService;
    private final PulseAnalysisProperties analysisProperties;
    private final PulseBillingProperties billingProperties;

    @Transactional
    public AnalysisRequestEntity startAnalysis(UserEntity user, ChannelEntity channel) {
        ensureNoActiveRequest(user.getId());
        if (billingProperties.isEnabled()) {
            return startPaid(user, channel);
        }
        return requestRepository.save(baseRequest(user, channel, RequestType.PAID));
    }

    @Transactional
    public AnalysisRequestEntity startFree(UserEntity user, ChannelEntity channel) {
        ensureNoActiveRequest(user.getId());
        AnalysisRequestEntity request = baseRequest(user, channel, RequestType.FREE);
        return requestRepository.save(request);
    }

    @Transactional
    public AnalysisRequestEntity startPaid(UserEntity user, ChannelEntity channel) {
        ensureNoActiveRequest(user.getId());
        if (user.getBalance() <= 0) {
            throw new IllegalStateException("Недостаточно запросов");
        }
        AnalysisRequestEntity request = requestRepository.save(baseRequest(user, channel, RequestType.PAID));
        userService.chargeRequest(user, request.getId());
        request.setBalanceCharged(true);
        return requestRepository.save(request);
    }

    @Transactional
    public boolean hasActiveRequest(Long userId) {
        failStaleActiveRequests(userId);
        return requestRepository.existsByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
    }

    @Transactional
    public int failStaleActiveRequests(Long userId) {
        Instant cutoff = Instant.now().minus(analysisProperties.getStaleRequestTimeoutMinutes(), ChronoUnit.MINUTES);
        List<AnalysisRequestEntity> stale = requestRepository.findByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
        int failed = 0;
        for (AnalysisRequestEntity request : stale) {
            Instant reference = request.getStartedAt() != null ? request.getStartedAt() : request.getCreatedAt();
            if (reference.isBefore(cutoff)) {
                request.setStatus(RequestStatus.FAILED);
                request.setErrorMessage("Анализ завис и был автоматически отменён. Запустите снова.");
                request.setCompletedAt(Instant.now());
                if (request.isBalanceCharged()) {
                    userService.refundRequest(request.getUserId(), request.getId());
                    request.setBalanceCharged(false);
                }
                requestRepository.save(request);
                failed++;
            }
        }
        return failed;
    }

    @Transactional(readOnly = true)
    public AnalysisRequestEntity getForUser(Long requestId, Long userId) {
        AnalysisRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Запрос не найден"));
        if (!request.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Нет доступа к запросу");
        }
        return request;
    }

    @Transactional
    public void updateProgress(Long requestId, RequestStatus status, short percent, String stage) {
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setStatus(status);
            request.setProgressPercent(percent);
            request.setProgressStage(stage);
            if (request.getStartedAt() == null) {
                request.setStartedAt(Instant.now());
            }
            requestRepository.save(request);
        });
    }

    @Transactional
    public void complete(Long requestId) {
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setStatus(RequestStatus.COMPLETED);
            request.setProgressPercent((short) 100);
            request.setCompletedAt(Instant.now());
            requestRepository.save(request);
        });
    }

    @Transactional
    public void fail(Long requestId, String error, boolean refund) {
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setStatus(RequestStatus.FAILED);
            request.setErrorMessage(error);
            request.setCompletedAt(Instant.now());
            if (refund && request.isBalanceCharged()) {
                userService.refundRequest(request.getUserId(), request.getId());
                request.setBalanceCharged(false);
            }
            
            requestRepository.save(request);
        });
    }

    private AnalysisRequestEntity baseRequest(UserEntity user, ChannelEntity channel, RequestType type) {
        LocalDate periodTo = LocalDate.now();
        LocalDate periodFrom = periodTo.minusDays(analysisProperties.getPeriodDays() - 1L);

        AnalysisRequestEntity request = new AnalysisRequestEntity();
        request.setUserId(user.getId());
        request.setChannelId(channel.getId());
        request.setType(type);
        request.setStatus(RequestStatus.PENDING);
        request.setPeriodFrom(periodFrom);
        request.setPeriodTo(periodTo);
        request.setProgressPercent((short) 0);
        request.setBalanceCharged(false);
        return request;
    }

    private void ensureNoActiveRequest(Long userId) {
        failStaleActiveRequests(userId);
        if (requestRepository.existsByUserIdAndStatusIn(userId, ACTIVE_STATUSES)) {
            throw new IllegalStateException(
                    "⏳ Я ещё разбираю прошлый канал. Дождитесь отчёта — и пришлите следующую ссылку.");
        }
    }
}
