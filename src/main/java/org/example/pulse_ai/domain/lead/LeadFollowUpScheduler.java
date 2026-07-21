package org.example.pulse_ai.domain.lead;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.HotLeadEntity;
import org.example.pulse_ai.persistence.repository.HotLeadRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** P0.8: напоминание админу, если лид молчит 24+ часов. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadFollowUpScheduler {

    private final HotLeadRepository hotLeadRepository;
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    @Transactional
    public void remindStaleLeads() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<HotLeadEntity> stale = hotLeadRepository
                .findByStatusInAndFollowUpSentFalseAndCreatedAtBefore(
                        List.of("NEW", "IN_PROGRESS"), cutoff);
        for (HotLeadEntity lead : stale) {
            notifyOwner(lead);
            lead.setFollowUpSent(true);
            hotLeadRepository.save(lead);
        }
    }

    private void notifyOwner(HotLeadEntity lead) {
        userRepository.findById(lead.getOwnerUserId()).ifPresent(user -> {
            if (user.getTelegramId() == null) {
                return;
            }
            String name = lead.getCommenterUsername() != null
                    ? "@" + lead.getCommenterUsername()
                    : TgHtml.esc(lead.getCommenterName() != null ? lead.getCommenterName() : "клиент");
            String text = "⏰ <b>Лид без ответа 24ч+</b>\n"
                    + name + " — «" + TgHtml.esc(trim(lead.getCommentText(), 80)) + "»\n\n"
                    + "Jarvis → 🔥 Лиды — дожмите или отметьте статус.";
            messageSender.sendTextSafe(user.getTelegramId(), text);
        });
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
