package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.handler.ProductChannelHandler;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductChannelScheduler {

    private final PulseProductChannelProperties properties;
    private final ProductChannelService productChannelService;
    private final ProductChannelHandler productChannelHandler;
    private final ProductStyleLearnerService styleLearner;

    /** 09:00 МСК — черновик «утреннего брифа» владельцу на утверждение */
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
        for (Long ownerId : properties.getOwnerTelegramIds()) {
            try {
                ProductChannelPostEntity draft = productChannelService.generateDraft(
                        ProductPostRubric.MORNING, ownerId, "Авто-черновик на утро");
                productChannelHandler.deliverDraftToOwner(ownerId, draft);
            } catch (Exception ex) {
                log.warn("Morning brief failed for owner {}: {}", ownerId, ex.getMessage());
            }
        }
    }
}
