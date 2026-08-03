package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.handler.ProductChannelHandler;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductChannelScheduler {

    private final PulseProductChannelProperties properties;
    private final ProductChannelService productChannelService;
    private final ProductChannelHandler productChannelHandler;
    private final ProductStyleLearnerService styleLearner;
    private final ProductStoryService storyService;

    /** 09:00 МСК — черновик «утреннего брифа» + рубрика дня владельцу на утверждение */
    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Moscow")
    public void morningBriefToOwners() {
        if (!properties.isEnabled() || !properties.isMorningBriefEnabled()) {
            return;
        }
        if (properties.getOwnerTelegramIds() == null || properties.getOwnerTelegramIds().isEmpty()) {
            return;
        }
        ProductChannelService.ChannelReadiness readiness = productChannelService.checkChannel();
        if (!readiness.ready()) {
            log.debug("Morning brief skipped: {}", readiness.message());
            return;
        }

        styleLearner.ensureFreshStyle();
        DayOfWeek dow = ZonedDateTime.now(ZoneId.of("Europe/Moscow")).getDayOfWeek();
        ProductPostRubric dayRubric = ProductChannelPrompts.rubricForDayOfWeek(dow.getValue());
        String extra = "Авто-черновик на утро. Рубрика дня по календарю: " + dayRubric.label()
                + ". Тон: product-growth Pulse AI (анонсы, прогресс, польза, голос за фичи).";

        for (Long ownerId : properties.getOwnerTelegramIds()) {
            try {
                ProductChannelPostEntity draft = productChannelService.generateDraft(
                        ProductPostRubric.MORNING, ownerId, extra);
                productChannelHandler.deliverDraftToOwner(ownerId, draft);
            } catch (Exception ex) {
                log.warn("Morning brief failed for owner {}: {}", ownerId, ex.getMessage());
            }
        }
    }

    /** Каждые 5 мин — сюжетные эпизоды по расписанию арки */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void publishDueStoryBeats() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int n = storyService.publishDueScheduled();
            if (n > 0) {
                log.info("Published {} story beat(s) from schedule", n);
            }
        } catch (Exception ex) {
            log.warn("Story schedule tick failed: {}", ex.getMessage());
        }
    }
}
