package org.example.pulse_ai.domain.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.AnalysisSnapshotEntity;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.AnalysisSnapshotRepository;
import org.example.pulse_ai.persistence.repository.ContentIdeaRepository;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.stats.model.PublishSlotMetric;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalysisSnapshotService {

    private final AnalysisSnapshotRepository snapshotRepository;
    private final ContentIdeaRepository ideaRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void save(Long requestId, AnalysisMetrics metrics) throws Exception {
        AnalysisSnapshotEntity snapshot = new AnalysisSnapshotEntity();
        snapshot.setRequestId(requestId);
        snapshot.setAvgViews(metrics.avgViews());
        snapshot.setAvgEngagementRate(metrics.avgEngagementRate());
        snapshot.setViewsDeltaPercent(metrics.viewsDeltaPercent());
        snapshot.setPostCount(metrics.postCount());
        snapshot.setBestPublishSlotsJson(objectMapper.writeValueAsString(metrics.bestSlots()));
        snapshot.setAvoidSlotsJson(objectMapper.writeValueAsString(metrics.avoidSlots()));
        snapshot.setTopPostsJson(objectMapper.writeValueAsString(metrics.topPosts()));
        snapshot.setWorstPostsJson(objectMapper.writeValueAsString(metrics.worstPosts()));
        snapshot.setWorkingTopicsJson(objectMapper.writeValueAsString(metrics.workingTopics()));
        snapshot.setDailyViewsJson(objectMapper.writeValueAsString(metrics.dailyViews()));
        snapshot.setFrequencyRecommendation(metrics.frequencyRecommendation());
        snapshotRepository.save(snapshot);
    }

    @Transactional
    public void saveDeepAnalysis(Long requestId, String deepAnalysis) {
        snapshotRepository.findById(requestId).ifPresent(snapshot -> {
            snapshot.setRawMetricsJson(deepAnalysis);
            try {
                List<DeepAnalysisSections.Section> sections = DeepAnalysisSections.parse(deepAnalysis);
                snapshot.setDeepAnalysisSectionsJson(objectMapper.writeValueAsString(sections));
            } catch (Exception ex) {
                // keep raw text only
            }
            snapshotRepository.save(snapshot);
        });
    }

    @Transactional(readOnly = true)
    public List<DeepAnalysisSections.Section> getSections(Long requestId) {
        AnalysisSnapshotEntity snapshot = snapshotRepository.findById(requestId).orElse(null);
        if (snapshot == null) {
            return List.of();
        }
        if (snapshot.getDeepAnalysisSectionsJson() != null) {
            try {
                return objectMapper.readValue(
                        snapshot.getDeepAnalysisSectionsJson(),
                        new TypeReference<List<DeepAnalysisSections.Section>>() {});
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (snapshot.getRawMetricsJson() != null) {
            return DeepAnalysisSections.parse(snapshot.getRawMetricsJson());
        }
        return List.of();
    }

    /** Бриф разбора для промптов идей/черновиков. */
    @Transactional(readOnly = true)
    public String contentBrief(Long requestId) {
        return AnalysisBriefForContent.fromSections(getSections(requestId));
    }

    @Transactional
    public void saveIdeas(List<ContentIdeaEntity> ideas) {
        ideaRepository.saveAll(ideas);
    }

    @Transactional
    public void replaceIdeas(Long requestId, List<ContentIdeaEntity> ideas) {
        ideaRepository.deleteByRequestId(requestId);
        ideaRepository.saveAll(ideas);
    }

    @Transactional(readOnly = true)
    public AnalysisSnapshotEntity getSnapshot(Long requestId) {
        return snapshotRepository.findById(requestId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ContentIdeaEntity> getIdeas(Long requestId) {
        return ideaRepository.findByRequestIdOrderBySortOrderAsc(requestId);
    }

    /** Лучшие слоты публикации (по охвату) из сохранённого анализа. Первый — самый сильный. */
    @Transactional(readOnly = true)
    public List<PublishSlotMetric> getBestSlots(Long requestId) {
        AnalysisSnapshotEntity snapshot = snapshotRepository.findById(requestId).orElse(null);
        if (snapshot == null || snapshot.getBestPublishSlotsJson() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    snapshot.getBestPublishSlotsJson(),
                    new TypeReference<List<PublishSlotMetric>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
