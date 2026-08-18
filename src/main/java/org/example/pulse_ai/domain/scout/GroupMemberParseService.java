package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.outreach.OutreachCampaignService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.GroupParseJobEntity;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.GroupParseJobRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMemberParseService {

    private static final int PARSE_LIMIT = 500;
    /** warm+ : отсекает cold/dead и ботов без username. */
    private static final int MIN_SCORE = 35;

    private final GroupParseJobRepository jobRepository;
    private final ScoutAccountService scoutAccountService;
    private final ScoutSessionGateway scoutGateway;
    private final OutreachCampaignService campaignService;
    private final ScoutActionLogService actionLogService;
    private final UserRepository userRepository;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    @Transactional
    public GroupParseJobEntity queueParse(Long userId, Long campaignId, String groupLink) {
        GroupParseJobEntity job = new GroupParseJobEntity();
        job.setUserId(userId);
        job.setCampaignId(campaignId);
        job.setGroupLink(groupLink.trim());
        job.setStatus("PENDING");
        return jobRepository.save(job);
    }

    @Transactional
    public void processPendingJobs() {
        List<GroupParseJobEntity> pending = jobRepository.findTop5ByStatusOrderByCreatedAtAsc("PENDING");
        for (GroupParseJobEntity job : pending) {
            processJob(job);
        }
    }

    private void processJob(GroupParseJobEntity job) {
        job.setStatus("RUNNING");
        jobRepository.save(job);

        var accountOpt = scoutAccountService.pickParserAccount();
        if (accountOpt.isEmpty()) {
            fail(job, "Нет PARSER/OBSERVER-аккаунта. Sender'ы не парсят — добавьте scout PARSER.");
            notifyOwner(job, "⚠️ Парсинг не стартовал: нет живого парсера. Напишите в поддержку.", null);
            return;
        }

        // Сначала join — иначе закрытые/invite-ссылки не откроются.
        ScoutSessionGateway.JoinResult join = scoutGateway.joinChat(accountOpt.get().getId(), job.getGroupLink());
        if (!join.ok()) {
            log.info("Join before parse #{}: {}", job.getId(), join.error());
        }

        ScoutSessionGateway.ParseAudienceResult result = scoutGateway.parseAudience(
                accountOpt.get().getId(), job.getGroupLink(), PARSE_LIMIT, MIN_SCORE);
        if (!result.ok()) {
            actionLogService.fail(accountOpt.get().getId(), job.getUserId(), "GROUP_PARSE",
                    job.getGroupLink(), result.error());
            fail(job, result.error());
            notifyOwner(job, "⚠️ Парсинг не удался: " + humanizeParseError(result.error()), null);
            return;
        }

        List<ScoutSessionGateway.AudienceMember> users = result.users();
        long hot = users.stream().filter(u -> "hot".equals(u.tier())).count();
        long warm = users.stream().filter(u -> "warm".equals(u.tier())).count();

        int added = 0;
        Long campaignId = job.getCampaignId();
        if (campaignId == null) {
            UserEntity owner = userRepository.findById(job.getUserId()).orElse(null);
            if (owner != null) {
                try {
                    OutreachCampaignEntity created = campaignService.createFromGroupLink(
                            owner, null, job.getGroupLink(), "INVITE");
                    campaignId = created.getId();
                    job.setCampaignId(campaignId);
                    jobRepository.save(job);
                } catch (Exception ex) {
                    log.warn("parse job #{} auto-campaign failed: {}", job.getId(), ex.getMessage());
                }
            }
        }
        if (campaignId != null) {
            StringBuilder sb = new StringBuilder();
            for (ScoutSessionGateway.AudienceMember u : users) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append('@').append(u.username());
            }
            try {
                added = campaignService.importUsernames(job.getUserId(), campaignId, sb.toString());
            } catch (Exception ex) {
                actionLogService.fail(accountOpt.get().getId(), job.getUserId(), "GROUP_PARSE",
                        job.getGroupLink(), ex.getMessage());
                fail(job, ex.getMessage());
                notifyOwner(job, "⚠️ Парсинг: ошибка импорта — " + ex.getMessage(), null);
                return;
            }
        }

        int found = added > 0 ? added : users.size();
        job.setStatus("DONE");
        job.setMembersFound(found);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        actionLogService.ok(accountOpt.get().getId(), job.getUserId(), "GROUP_PARSE",
                job.getGroupLink() + " → " + found + " (hot=" + hot + " warm=" + warm + ")");
        log.info("Group parse job #{} done: {} liquid members", job.getId(), found);

        String title = join.title() != null && !join.title().isBlank() ? join.title() : job.getGroupLink();
        String msg = "✅ <b>Парсинг ЦА готов</b>\n"
                + "«" + title + "»\n\n"
                + "Живых с @username: <b>" + found + "</b>\n"
                + "• hot: <b>" + hot + "</b> · warm: <b>" + warm + "</b>\n"
                + "<i>Мёртвые, боты и без username отсеяны.</i>";
        Long doneCampaignId = job.getCampaignId();
        if (doneCampaignId != null) {
            msg += "\n\nВ кампании #" + doneCampaignId + ": <b>" + added + "</b>. Дальше — «Запустить».";
            notifyOwner(job, msg, keyboards.outreachParseDoneInline(doneCampaignId));
        } else {
            msg += "\n\nОткройте 📨 Рассылка и создайте кампанию.";
            notifyOwner(job, msg, null);
        }
    }

    private void fail(GroupParseJobEntity job, String error) {
        job.setStatus("FAILED");
        job.setLastError(error);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
    }

    private void notifyOwner(GroupParseJobEntity job, String text,
                             org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
        userRepository.findById(job.getUserId()).map(UserEntity::getTelegramId).ifPresent(tgId -> {
            if (keyboard != null) {
                messageSender.sendTextWithInlineSafe(tgId, text, keyboard);
            } else {
                messageSender.sendTextSafe(tgId, text);
            }
        });
    }

    static String humanizeParseError(String raw) {
        if (raw == null || raw.isBlank()) {
            return "неизвестная ошибка";
        }
        String e = raw.toLowerCase();
        if (e.contains("chat admin") || e.contains("getparticipants") || e.contains("privileges")) {
            return "Список участников закрыт (нужна админка или парсим по сообщениям). "
                    + "Зайди парсером в группу / возьми публичный суперчат. Детали: " + raw;
        }
        return raw;
    }
}
