package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.outreach.OutreachCampaignService;
import org.example.pulse_ai.persistence.entity.GroupParseJobEntity;
import org.example.pulse_ai.persistence.repository.GroupParseJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMemberParseService {

    private static final int PARSE_LIMIT = 500;

    private final GroupParseJobRepository jobRepository;
    private final ScoutAccountService scoutAccountService;
    private final ScoutSessionGateway scoutGateway;
    private final OutreachCampaignService campaignService;

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

        var accountOpt = scoutAccountService.pickOutreachAccount();
        if (accountOpt.isEmpty()) {
            accountOpt = scoutAccountService.activeOutreach().stream().findFirst();
        }
        if (accountOpt.isEmpty()) {
            fail(job, "Нет scout-аккаунта. Добавьте запись в scout_accounts.");
            return;
        }

        ScoutSessionGateway.ParseMembersResult result = scoutGateway.parseGroupMembers(
                accountOpt.get().getId(), job.getGroupLink(), PARSE_LIMIT);
        if (!result.ok()) {
            fail(job, result.error());
            return;
        }

        int added = 0;
        if (job.getCampaignId() != null) {
            StringBuilder sb = new StringBuilder();
            for (String u : result.usernames()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append('@').append(u);
            }
            try {
                added = campaignService.importUsernames(job.getUserId(), job.getCampaignId(), sb.toString());
            } catch (Exception ex) {
                fail(job, ex.getMessage());
                return;
            }
        }

        job.setStatus("DONE");
        job.setMembersFound(added > 0 ? added : result.usernames().size());
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        log.info("Group parse job #{} done: {} members", job.getId(), job.getMembersFound());
    }

    private void fail(GroupParseJobEntity job, String error) {
        job.setStatus("FAILED");
        job.setLastError(error);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
    }
}
