package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.channel.ChannelProfileService;
import org.example.pulse_ai.domain.request.RequestStatus;
import org.example.pulse_ai.domain.request.RequestType;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.handler.ResultHandler;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.telegram.LiveProgressService;
import org.example.pulse_ai.visual.AnalysisChartPack;
import org.example.pulse_ai.visual.AnalysisChartRenderer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisWorker {

    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final AnalyticsService analyticsService;
    private final AnalysisSnapshotService snapshotService;
    private final IdeasGenerationService ideasGenerationService;
    private final AnalysisChartRenderer chartRenderer;
    private final ResultHandler resultHandler;
    private final UserService userService;
    private final ChannelSyncService channelSyncService;
    private final PulseBillingProperties billingProperties;
    private final PulseAnalysisProperties analysisProperties;
    private final ChannelDeepAnalysisService deepAnalysisService;
    private final org.example.pulse_ai.stats.external.ExternalMetricsService externalMetricsService;
    private final org.example.pulse_ai.stats.external.TgstatApiClient tgstatApiClient;
    private final LiveProgressService liveProgressService;
    private final ChannelProfileService channelProfileService;

    @Async
    public void runAsync(Long requestId, long chatId) {
        AnalysisRequestEntity request = null;
        LiveProgressService.LiveProgress progress = null;
        try {
            request = requestRepository.findById(requestId)
                    .orElseThrow(() -> new IllegalStateException("Request not found"));
            ChannelEntity channel = channelRepository.findById(request.getChannelId())
                    .orElseThrow(() -> new IllegalStateException("Channel not found"));

            progress = liveProgressService.start(chatId,
                    "📊 <b>Анализ канала</b>\n📢 " + org.example.pulse_ai.text.TgHtml.b(channel.getTitle()));

            update(requestId, RequestStatus.COLLECTING_STATS, (short) 10, "Сбор постов");
            progress.step(0);
            ChannelSyncService.SyncResult sync = channelSyncService.syncForAnalysis(channel);
            log.info("Request {}: posts ready, total={}, sanitized={}, category={}",
                    requestId, sync.totalPosts(), sync.sanitizedPosts(), sync.category());

            update(requestId, RequestStatus.ANALYZING, (short) 30, "Расчёт метрик");
            progress.step(1);
            AnalysisMetrics metrics = analyticsService.analyze(
                    channel.getId(),
                    request.getPeriodFrom(),
                    request.getPeriodTo()
            );

            if (metrics.postCount() == 0) {
                progress.finish("❌ Не нашёл постов для анализа.");
                progress = null;
                fail(request, chatId, """
                        Нет постов для анализа.
                        Пришлите ссылку на открытый канал — бот подтянет посты сам.""");
                return;
            }

            try {
                snapshotService.save(requestId, metrics);
            } catch (Exception ex) {
                log.warn("Не удалось сохранить snapshot для request {}: {}", requestId, ex.getMessage());
            }

            int channelSubscribers = channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0;
            boolean sparseChannel = deepAnalysisService.needsSparseAnalysis(metrics, channelSubscribers);

            update(requestId, RequestStatus.ANALYZING, (short) 45, "Внешние площадки");
            progress.step(2);
            org.example.pulse_ai.stats.external.ExternalChannelMetrics external =
                    org.example.pulse_ai.stats.external.ExternalChannelMetrics.unavailable("skip", "");
            String externalSummary = null;
            if (!sparseChannel && metrics.postCount() >= analysisProperties.getMinPostsFull()) {
                external = tgstatApiClient.getStat(channel.getUsername())
                        .orElseGet(() -> externalMetricsService.bestMetrics(channel.getUsername()));
                if (isExternalMetricsTrustworthy(external, metrics, channelSubscribers)) {
                    externalSummary = externalMetricsService.describeForLlm(external);
                } else {
                    external = org.example.pulse_ai.stats.external.ExternalChannelMetrics.unavailable("skip", "");
                    log.info("Request {}: skipped external metrics (inconsistent with channel data)", requestId);
                }
            }

            Double errPercent = external != null && external.available() ? external.err() : null;
            int subscribersForNiche = external != null && external.subscribers() != null
                    ? external.subscribers()
                    : channelSubscribers;
            Double reachPercent = subscribersForNiche > 0 && metrics.avgViews() > 0
                    ? metrics.avgViews() * 100.0 / subscribersForNiche
                    : null;

            try {
                channelProfileService.saveFromAnalysis(
                        channel,
                        request,
                        metrics,
                        external,
                        sync.category(),
                        subscribersForNiche
                );
            } catch (Exception ex) {
                log.warn("Не удалось сохранить профиль канала для request {}: {}", requestId, ex.getMessage());
            }

            AnalysisChartPack charts = chartRenderer.render(channel.getTitle(), metrics, errPercent, reachPercent);

            update(requestId, RequestStatus.GENERATING_IDEAS, (short) 65, "LLM-разбор канала");
            progress.step(3);
            String deepAnalysis;
            if (sparseChannel) {
                deepAnalysis = deepAnalysisService.sparseChannelAnalysis(
                        channel.getTitle(), metrics, channelSubscribers);
            } else {
                deepAnalysis = deepAnalysisService.analyzeChannel(
                        channel.getId(),
                        channel.getTitle(),
                        metrics,
                        request.getPeriodFrom(),
                        request.getPeriodTo(),
                        externalSummary
                );
            }
            snapshotService.saveDeepAnalysis(requestId, deepAnalysis);

            update(requestId, RequestStatus.GENERATING_IDEAS, (short) 85, "3 сильные идеи");
            progress.step(4);
            int ideaCount = billingProperties.ideasFor(request.getType());
            List<ContentIdeaEntity> ideas = ideasGenerationService.generateIdeas(
                    requestId,
                    channel.getTitle(),
                    metrics,
                    ideaCount,
                    analysisProperties.getLlmTimeoutSeconds()
            );
            snapshotService.saveIdeas(ideas);
            userService.addIdeasReceived(request.getUserId(), ideas.size());

            update(requestId, RequestStatus.GENERATING_POSTS, (short) 100, "Готово");
            progress.dismiss();
            progress = null;

            resultHandler.deliverQuickStats(chatId, requestId, channel, charts);
            resultHandler.deliverDeepAnalysis(chatId, requestId, deepAnalysis);

            requestRepository.findById(requestId).ifPresent(r -> {
                r.setStatus(RequestStatus.COMPLETED);
                r.setProgressPercent((short) 100);
                r.setCompletedAt(java.time.Instant.now());
                requestRepository.save(r);
            });

            if (billingProperties.isEnabled() && request.getType() == RequestType.FREE) {
                userService.markFreeAnalysisUsedById(request.getUserId());
            }

            resultHandler.finishSession(chatId);
        } catch (Exception ex) {
            log.error("Analysis failed for request {}", requestId, ex);
            if (progress != null) {
                progress.stop();
            }
            if (request != null) {
                fail(request, chatId, "Ошибка анализа: " + ex.getMessage());
            } else {
                resultHandler.sendFailure(chatId, "Ошибка анализа: " + ex.getMessage());
            }
        }
    }

    private void fail(AnalysisRequestEntity request, long chatId, String message) {
        boolean refund = request.isBalanceCharged();
        request.setStatus(RequestStatus.FAILED);
        request.setErrorMessage(truncateError(message));
        request.setCompletedAt(java.time.Instant.now());
        if (refund) {
            userService.refundRequest(request.getUserId(), request.getId());
            request.setBalanceCharged(false);
        }
        try {
            requestRepository.save(request);
        } catch (Exception ex) {
            log.warn("Не удалось сохранить статус ошибки request {}: {}", request.getId(), ex.getMessage());
        }
        resultHandler.sendFailure(chatId, truncateError(message));
        resultHandler.finishSession(chatId);
    }

    private static String truncateError(String message) {
        if (message == null) {
            return "Ошибка анализа";
        }
        return message.length() <= 240 ? message : message.substring(0, 237) + "...";
    }

    private static boolean isExternalMetricsTrustworthy(
            org.example.pulse_ai.stats.external.ExternalChannelMetrics external,
            AnalysisMetrics metrics,
            int channelSubscribers
    ) {
        if (external == null || !external.available()) {
            return false;
        }
        if (external.subscribers() != null && channelSubscribers > 0 && channelSubscribers <= 200) {
            if (external.subscribers() > channelSubscribers * 5) {
                return false;
            }
        }
        int subs = external.subscribers() != null ? external.subscribers() : channelSubscribers;
        if (external.avgReach() != null && subs > 0 && subs <= 200
                && external.avgReach() > Math.max(subs * 10, 1_000)) {
            return false;
        }
        if (metrics.postCount() < 5 && external.avgReach() != null && external.avgReach() > 2_000) {
            return false;
        }
        return true;
    }

    private void update(Long requestId, RequestStatus status, short percent, String stage) {
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setStatus(status);
            request.setProgressPercent(percent);
            request.setProgressStage(stage);
            if (request.getStartedAt() == null) {
                request.setStartedAt(java.time.Instant.now());
            }
            requestRepository.save(request);
        });
    }
}
