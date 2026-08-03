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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Антиспам уведомлений о skip (раз на кампанию / 30 мин). */
    private final Map<Long, Long> lastSkipNotifyAt = new ConcurrentHashMap<>();

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
            log.warn("Outreach skipped campaign #{}: {}", campaign.getId(), outcome.detail());
            if (outcome.detail() != null && outcome.detail().contains("SENDER")) {
                // Без sender'а крутить очередь бессмысленно — ставим на паузу.
                campaign.setStatus("PAUSED");
                campaignRepository.save(campaign);
                notifyOwner(campaign, "⏸ Кампания #" + campaign.getId()
                        + " на паузе: нет ACTIVE SENDER/OUTREACH.\n"
                        + "Сейчас в sidecar только PARSER/OBSERVER — они ЛС не шлют.\n"
                        + "Добавь SENDER (см. docs/SCOUT_OPS.md) и нажми ▶️ снова.");
                return;
            }
            maybeNotifySkip(campaign, outcome.detail());
            return;
        }
        if (outcome.sent()) {
            notifyOwner(campaign, "✅ ЛС ушло @" + next.get().getUsername()
                    + " (кампания #" + campaign.getId() + ")");
            return;
        }
        log.warn("Outreach failed @{} campaign #{}: {}",
                next.get().getUsername(), campaign.getId(), outcome.detail());
        notifyOwner(campaign, "⚠️ Не отправилось @" + next.get().getUsername()
                + ": " + outcome.detail());
    }

    private void maybeNotifySkip(OutreachCampaignEntity campaign, String detail) {
        long now = System.currentTimeMillis();
        Long prev = lastSkipNotifyAt.get(campaign.getId());
        if (prev != null && now - prev < 30 * 60_000L) {
            return;
        }
        lastSkipNotifyAt.put(campaign.getId(), now);
        notifyOwner(campaign, "⏸ Кампания #" + campaign.getId()
                + " ждёт: " + detail
                + "\nПроверь sidecar (8090) и ACTIVE SENDER в скаутах.");
    }

    private void notifyOwner(OutreachCampaignEntity campaign, String text) {
        userRepository.findById(campaign.getUserId()).map(UserEntity::getTelegramId).ifPresent(tgId ->
                messageSender.sendTextSafe(tgId, text));
    }
}
