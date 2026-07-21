package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntitlementEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.UserEntitlementRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Понедельник 10:00 МСК — рассылка еженедельного дайджеста владельцам активного перка DIGEST.
 * Гейтится реальными записями entitlement (не hasAccess), поэтому при billing.enabled=false рассылки нет.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyDigestScheduler {

    private final UserEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final ChannelSyncService channelSyncService;
    private final WeeklyDigestService digestService;
    private final TelegramMessageSender messageSender;

    @Scheduled(cron = "0 0 10 * * MON", zone = "Europe/Moscow")
    public void sendWeeklyDigests() {
        List<UserEntitlementEntity> active =
                entitlementRepository.findAllActiveByPerkCode(PerkType.DIGEST.code(), Instant.now());
        if (active.isEmpty()) {
            return;
        }
        log.info("Weekly digest: {} active subscriptions", active.size());
        for (UserEntitlementEntity ent : active) {
            try {
                deliver(ent.getUserId());
            } catch (Exception ex) {
                log.warn("Weekly digest failed for user {}: {}", ent.getUserId(), ex.getMessage());
            }
        }
    }

    private void deliver(Long userId) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getActiveChannelId() == null) {
            return;
        }
        ChannelEntity channel = channelRepository.findById(user.getActiveChannelId()).orElse(null);
        if (channel == null) {
            return;
        }
        channelSyncService.syncForAnalysis(channel);
        String digest = digestService.buildDigest(channel);
        messageSender.sendTextSafe(user.getTelegramId(), TgHtml.fromMarkdown(digest));
    }
}
