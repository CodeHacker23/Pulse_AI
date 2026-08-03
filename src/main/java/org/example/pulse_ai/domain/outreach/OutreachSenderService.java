package org.example.pulse_ai.domain.outreach;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseOutreachProperties;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
import org.example.pulse_ai.domain.scout.ScoutActionLogService;
import org.example.pulse_ai.domain.scout.ScoutSessionGateway;
import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.repository.OutreachProspectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutreachSenderService {

    private final PulseOutreachProperties outreachProperties;
    private final PulseScoutProperties scoutProperties;
    private final ScoutAccountService scoutAccountService;
    private final ScoutSessionGateway scoutGateway;
    private final OutreachCampaignService campaignService;
    private final OutreachProspectRepository prospectRepository;
    private final ScoutActionLogService actionLogService;

    @Transactional
    public SendOutcome trySend(OutreachProspectEntity prospect, Long ownerUserId) {
        if (!outreachProperties.isDispatchEnabled()) {
            return SendOutcome.skipped("dispatch disabled");
        }
        if (!scoutProperties.sidecarConfigured()) {
            return SendOutcome.skipped("sidecar not configured");
        }
        Optional<ScoutAccountEntity> accountOpt = scoutAccountService.pickOutreachAccount();
        if (accountOpt.isEmpty()) {
            return SendOutcome.skipped("no ACTIVE SENDER/OUTREACH account");
        }
        ScoutAccountEntity account = accountOpt.get();
        String text = prospect.getPersonalizedText();
        if (text == null || text.isBlank()) {
            return SendOutcome.failed("empty message text");
        }
        String username = prospect.getUsername();
        if (username == null || username.isBlank()) {
            return SendOutcome.failed("empty username");
        }

        ScoutSessionGateway.SendResult result = scoutGateway.sendDirectMessage(
                account.getId(), username, text);
        scoutAccountService.recordSend(account.getId(), result.ok(), result.error());

        if (result.ok()) {
            prospect.setScoutAccountId(account.getId());
            prospectRepository.save(prospect);
            campaignService.markSent(prospect.getId(), ownerUserId);
            actionLogService.ok(account.getId(), ownerUserId, "DM_SEND",
                    "@" + username + " via " + account.getLabel());
            return SendOutcome.sent(account.getLabel());
        }
        campaignService.markFailed(prospect.getId(), result.error());
        actionLogService.fail(account.getId(), ownerUserId, "DM_SEND",
                "@" + username, result.error());
        return SendOutcome.failed(result.error());
    }

    public record SendOutcome(boolean sent, boolean skipped, String detail) {
        public static SendOutcome sent(String account) {
            return new SendOutcome(true, false, account);
        }

        public static SendOutcome failed(String detail) {
            return new SendOutcome(false, false, detail);
        }

        public static SendOutcome skipped(String detail) {
            return new SendOutcome(false, true, detail);
        }
    }
}
