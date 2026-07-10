package org.example.pulse_ai.domain.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelProfileSnapshotEntity;
import org.example.pulse_ai.persistence.repository.ChannelProfileSnapshotRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.stats.external.ExternalChannelMetrics;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Сохраняет снимок метрик канала после каждого анализа — фундамент Модуля A (база ниш).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelProfileService {

    private final ChannelProfileSnapshotRepository profileRepository;
    private final ChannelRepository channelRepository;

    @Transactional
    public void saveFromAnalysis(
            ChannelEntity channel,
            AnalysisRequestEntity request,
            AnalysisMetrics metrics,
            ExternalChannelMetrics external,
            String category,
            int subscribers
    ) {
        ChannelProfileSnapshotEntity profile = new ChannelProfileSnapshotEntity();
        profile.setChannelId(channel.getId());
        profile.setRequestId(request.getId());
        profile.setUsername(channel.getUsername());
        profile.setTitle(channel.getTitle());
        profile.setCategory(blankToNull(category));
        profile.setSubscriberCount(subscribers > 0 ? subscribers : channel.getSubscriberCount());
        profile.setPostCount(metrics.postCount());
        profile.setAvgViews(metrics.avgViews());
        profile.setPeriodFrom(request.getPeriodFrom());
        profile.setPeriodTo(request.getPeriodTo());

        if (subscribers > 0 && metrics.avgViews() > 0) {
            double reach = metrics.avgViews() * 100.0 / subscribers;
            profile.setReachPercent(BigDecimal.valueOf(reach).setScale(1, RoundingMode.HALF_UP));
        }
        if (external != null) {
            if (external.avgReach() != null) {
                profile.setAvgReach(external.avgReach());
            }
            if (external.err() != null) {
                profile.setErrPercent(BigDecimal.valueOf(external.err()).setScale(1, RoundingMode.HALF_UP));
            }
            if (external.citationIndex() != null) {
                profile.setCitationIndex(BigDecimal.valueOf(external.citationIndex()).setScale(2, RoundingMode.HALF_UP));
            }
            if (profile.getCategory() == null && external.category() != null) {
                profile.setCategory(external.category());
            }
            if (profile.getSubscriberCount() == null && external.subscribers() != null) {
                profile.setSubscriberCount(external.subscribers());
            }
        }

        profileRepository.save(profile);

        if (profile.getCategory() != null) {
            channel.setCategory(profile.getCategory());
        }
        if (profile.getSubscriberCount() != null && profile.getSubscriberCount() > 0) {
            channel.setSubscriberCount(profile.getSubscriberCount());
        }
        channelRepository.save(channel);

        log.info("Channel profile saved: channelId={}, requestId={}, category={}",
                channel.getId(), request.getId(), profile.getCategory());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
