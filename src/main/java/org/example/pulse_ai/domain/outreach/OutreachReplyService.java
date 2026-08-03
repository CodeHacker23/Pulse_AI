package org.example.pulse_ai.domain.outreach;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.HotLeadEntity;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.example.pulse_ai.persistence.repository.HotLeadRepository;
import org.example.pulse_ai.persistence.repository.OutreachCampaignRepository;
import org.example.pulse_ai.persistence.repository.OutreachProspectRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Ответ на outbound DM → CRM (лид) + статус prospect REPLIED.
 * Вызов: веб-админка / будущий sidecar webhook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutreachReplyService {

    private final OutreachProspectRepository prospectRepository;
    private final OutreachCampaignRepository campaignRepository;
    private final HotLeadRepository hotLeadRepository;
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;

    @Transactional
    public boolean markReplied(long prospectId, String replySnippet) {
        Optional<OutreachProspectEntity> opt = prospectRepository.findById(prospectId);
        if (opt.isEmpty()) {
            return false;
        }
        OutreachProspectEntity prospect = opt.get();
        if (!"REPLIED".equals(prospect.getStatus())) {
            prospect.setStatus("REPLIED");
            prospect.setRepliedAt(Instant.now());
            prospectRepository.save(prospect);
        }

        OutreachCampaignEntity campaign = campaignRepository.findById(prospect.getCampaignId()).orElse(null);
        if (campaign != null) {
            campaign.setReplyCount(campaign.getReplyCount() + 1);
            campaignRepository.save(campaign);

            HotLeadEntity lead = new HotLeadEntity();
            lead.setOwnerUserId(campaign.getUserId());
            lead.setChannelId(campaign.getOwnerChannelId() != null ? campaign.getOwnerChannelId() : 0L);
            lead.setDiscussionChatId(-prospectId);
            lead.setCommentMessageId(prospectId);
            lead.setCommenterUsername(prospect.getUsername());
            lead.setCommenterName(prospect.getDisplayName());
            lead.setCommentText(replySnippet != null ? replySnippet : "(ответ на outbound)");
            lead.setCategory("OUTREACH_REPLY");
            lead.setReason("Ответ на рассылку кампании #" + campaign.getId());
            lead.setStatus("NEW");
            lead.setSuggestedReply("Спасибо за ответ! Уточните, удобно коротко созвониться / ответить в боте?");
            try {
                hotLeadRepository.save(lead);
            } catch (Exception ex) {
                log.debug("Hot lead from outreach reply skipped: {}", ex.getMessage());
            }

            userRepository.findById(campaign.getUserId()).ifPresent(user -> {
                if (user.getTelegramId() != null) {
                    messageSender.sendTextSafe(user.getTelegramId(),
                            "💬 <b>Ответ на рассылку</b>\n"
                                    + "@" + (prospect.getUsername() != null ? prospect.getUsername() : "?")
                                    + " ответил на кампанию #" + campaign.getId() + ".\n"
                                    + "Pulse Ассистент → Лиды и продажи.");
                }
            });
        }
        return true;
    }
}
