package org.example.pulse_ai.domain.outreach;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseOutreachProperties;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.OutreachCampaignRepository;
import org.example.pulse_ai.persistence.repository.OutreachProspectRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutreachDispatchWorker {

    private final PulseOutreachProperties properties;
    private final OutreachCampaignRepository campaignRepository;
    private final OutreachProspectRepository prospectRepository;
    private final OutreachCampaignService campaignService;
    private final OutreachSenderService senderService;
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;

    @Scheduled(fixedDelay = 90_000, initialDelay = 45_000)
    public void processQueue() {
        if (!properties.isDispatchEnabled()) {
            return;
        }
        List<OutreachCampaignEntity> running = campaignRepository.findByStatus("RUNNING");
        for (OutreachCampaignEntity campaign : running) {
            processCampaign(campaign);
        }
    }

    private void processCampaign(OutreachCampaignEntity campaign) {
        if (campaignService.sendsRemainingThisMonth(campaign.getUserId()) <= 0) {
            campaign.setStatus("PAUSED");
            campaignRepository.save(campaign);
            notifyOwner(campaign, "⏸ Кампания #" + campaign.getId()
                    + " на паузе: исчерпан месячный лимит рассылок.");
            return;
        }
        if (campaign.getSentCount() >= campaign.getDailyLimit()) {
            return;
        }
        Optional<OutreachProspectEntity> next = prospectRepository
                .findFirstByCampaignIdAndStatusOrderByCreatedAtAsc(campaign.getId(), "PENDING");
        if (next.isEmpty()) {
            campaign.setStatus("COMPLETED");
            campaignRepository.save(campaign);
            notifyOwner(campaign, "✅ Кампания #" + campaign.getId() + " завершена.");
            return;
        }

        OutreachSenderService.SendOutcome outcome = senderService.trySend(next.get(), campaign.getUserId());
        if (outcome.skipped()) {
            log.debug("Outreach skipped campaign #{}: {}", campaign.getId(), outcome.detail());
            return;
        }
        if (!outcome.sent()) {
            log.warn("Outreach failed @{} campaign #{}: {}",
                    next.get().getUsername(), campaign.getId(), outcome.detail());
        }
    }

    private void notifyOwner(OutreachCampaignEntity campaign, String text) {
        userRepository.findById(campaign.getUserId()).map(UserEntity::getTelegramId).ifPresent(tgId ->
                messageSender.sendTextSafe(tgId, text));
    }
}
