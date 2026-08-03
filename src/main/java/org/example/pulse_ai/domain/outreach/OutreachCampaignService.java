package org.example.pulse_ai.domain.outreach;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.config.PulseOutreachProperties;
import org.example.pulse_ai.domain.entitlement.AssistantQuotaService;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachMonthlyUsageEntity;
import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.OutreachCampaignRepository;
import org.example.pulse_ai.persistence.repository.OutreachMonthlyUsageRepository;
import org.example.pulse_ai.persistence.repository.OutreachProspectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutreachCampaignService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final Pattern USERNAME = Pattern.compile("@?([a-zA-Z][a-zA-Z0-9_]{4,31})");
    private static final Pattern GROUP_LINK = Pattern.compile(
            "(?i)(?:https?://)?t\\.me/(\\+|joinchat/|)[\\w-]+");

    private final OutreachCampaignRepository campaignRepository;
    private final OutreachProspectRepository prospectRepository;
    private final OutreachMonthlyUsageRepository usageRepository;
    private final PulseOutreachProperties properties;
    private final AssistantQuotaService assistantQuotaService;
    private final LlmService llmService;

    @Transactional(readOnly = true)
    public List<OutreachCampaignEntity> listCampaigns(Long userId) {
        return campaignRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<OutreachCampaignEntity> getCampaign(Long userId, Long campaignId) {
        return campaignRepository.findByIdAndUserId(campaignId, userId);
    }

    @Transactional(readOnly = true)
    public List<OutreachProspectEntity> prospects(Long campaignId) {
        return prospectRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId);
    }

    @Transactional(readOnly = true)
    public int sendsRemainingThisMonth(Long userId) {
        return assistantQuotaService.dmQuota(userId).remaining();
    }

    @Transactional(readOnly = true)
    public int monthlyDmLimit(Long userId) {
        AssistantQuotaService.DmQuotaSnapshot snap = assistantQuotaService.dmQuota(userId);
        return snap.base() + snap.topupRemaining();
    }

    @Transactional
    public OutreachCampaignEntity createCampaign(
            UserEntity user,
            Long ownerChannelId,
            String scenario,
            String rawSource,
            String messageTemplate
    ) {
        long active = campaignRepository.countByUserIdAndStatusIn(
                user.getId(), List.of("DRAFT", "RUNNING", "QUEUED"));
        if (active >= properties.getMaxCampaignsPerUser()) {
            throw new IllegalStateException("Лимит активных кампаний: " + properties.getMaxCampaignsPerUser());
        }

        ParsedSource parsed = parseSource(rawSource);
        if (parsed.usernames().isEmpty() && parsed.groupLink() == null) {
            throw new IllegalArgumentException(
                    "Нужен хотя бы один @username или ссылка на группу для парсинга.");
        }
        if (messageTemplate == null || messageTemplate.isBlank()) {
            throw new IllegalArgumentException("Текст сообщения не может быть пустым.");
        }
        if (messageTemplate.length() > 900) {
            throw new IllegalArgumentException("Сообщение слишком длинное (макс. 900 символов).");
        }

        OutreachCampaignEntity campaign = new OutreachCampaignEntity();
        campaign.setUserId(user.getId());
        campaign.setOwnerChannelId(ownerChannelId);
        campaign.setScenario(scenario != null ? scenario : "INVITE");
        campaign.setName(buildName(scenario, parsed));
        campaign.setSourceRef(parsed.groupLink());
        campaign.setMessageTemplate(messageTemplate.trim());
        campaign.setDailyLimit(properties.getDefaultDailyLimit());
        campaign.setStatus(parsed.usernames().isEmpty() ? "DRAFT" : "DRAFT");
        OutreachCampaignEntity saved = campaignRepository.save(campaign);

        for (String username : parsed.usernames()) {
            OutreachProspectEntity prospect = new OutreachProspectEntity();
            prospect.setCampaignId(saved.getId());
            prospect.setUsername(username.toLowerCase(Locale.ROOT));
            prospect.setStatus("PENDING");
            prospectRepository.save(prospect);
        }
        return saved;
    }

    @Transactional
    public OutreachCampaignEntity startCampaign(Long userId, Long campaignId) {
        OutreachCampaignEntity campaign = campaignRepository.findByIdAndUserId(campaignId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Кампания не найдена"));
        long pending = prospectRepository.countByCampaignIdAndStatus(campaignId, "PENDING");
        if (pending == 0 && campaign.getSourceRef() == null) {
            throw new IllegalStateException("Нет получателей. Добавьте @username.");
        }
        if (sendsRemainingThisMonth(userId) <= 0) {
            throw new IllegalStateException(
                    "Лимит рассылок на месяц исчерпан. Докупите ЛС в «💳 Тарифы» или дождитесь нового месяца.");
        }
        campaign.setStatus("RUNNING");
        if (campaign.getStartedAt() == null) {
            campaign.setStartedAt(Instant.now());
        }
        return campaignRepository.save(campaign);
    }

    @Transactional
    public OutreachCampaignEntity pauseCampaign(Long userId, Long campaignId) {
        OutreachCampaignEntity campaign = campaignRepository.findByIdAndUserId(campaignId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Кампания не найдена"));
        campaign.setStatus("PAUSED");
        return campaignRepository.save(campaign);
    }

    @Transactional
    public int importUsernames(Long userId, Long campaignId, String raw) {
        OutreachCampaignEntity campaign = campaignRepository.findByIdAndUserId(campaignId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Кампания не найдена"));
        ParsedSource parsed = parseSource(raw);
        int added = 0;
        for (String username : parsed.usernames()) {
            boolean exists = prospectRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId).stream()
                    .anyMatch(p -> username.equalsIgnoreCase(p.getUsername()));
            if (exists) {
                continue;
            }
            OutreachProspectEntity prospect = new OutreachProspectEntity();
            prospect.setCampaignId(campaign.getId());
            prospect.setUsername(username.toLowerCase(Locale.ROOT));
            prospect.setStatus("PENDING");
            prospectRepository.save(prospect);
            added++;
        }
        return added;
    }

    @Transactional
    public void personalizePending(Long campaignId, String channelTitle) {
        OutreachCampaignEntity campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) {
            return;
        }
        String template = campaign.getMessageTemplate();
        for (OutreachProspectEntity prospect : prospectRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId)) {
            if (!"PENDING".equals(prospect.getStatus()) || prospect.getPersonalizedText() != null) {
                continue;
            }
            prospect.setPersonalizedText(personalize(template, prospect.getUsername(), channelTitle));
            prospectRepository.save(prospect);
        }
    }

    @Transactional
    public boolean markSent(Long prospectId, Long userId) {
        OutreachProspectEntity prospect = prospectRepository.findById(prospectId).orElse(null);
        if (prospect == null) {
            return false;
        }
        OutreachCampaignEntity campaign = campaignRepository.findByIdAndUserId(prospect.getCampaignId(), userId)
                .orElse(null);
        if (campaign == null) {
            return false;
        }
        AssistantQuotaService.DmQuotaSnapshot snap = assistantQuotaService.dmQuota(userId);
        if (snap.remaining() <= 0) {
            return false;
        }
        prospect.setStatus("SENT");
        prospect.setSentAt(Instant.now());
        prospectRepository.save(prospect);
        campaign.setSentCount(campaign.getSentCount() + 1);
        campaignRepository.save(campaign);
        incrementMonthlyUsage(userId);
        if (snap.used() >= snap.base()) {
            assistantQuotaService.consumeTopupDm(userId);
        }
        return true;
    }

    @Transactional
    public void markFailed(Long prospectId, String error) {
        prospectRepository.findById(prospectId).ifPresent(p -> {
            p.setStatus("FAILED");
            p.setLastError(error);
            prospectRepository.save(p);
        });
    }

    private void incrementMonthlyUsage(Long userId) {
        String monthKey = monthKey();
        OutreachMonthlyUsageEntity.Pk pk = new OutreachMonthlyUsageEntity.Pk();
        pk.setUserId(userId);
        pk.setMonthKey(monthKey);
        OutreachMonthlyUsageEntity usage = usageRepository.findById(pk)
                .orElseGet(() -> {
                    OutreachMonthlyUsageEntity u = new OutreachMonthlyUsageEntity();
                    u.setUserId(userId);
                    u.setMonthKey(monthKey);
                    return u;
                });
        usage.setSentCount(usage.getSentCount() + 1);
        usageRepository.save(usage);
    }

    private String personalize(String template, String username, String channelTitle) {
        String hook = "";
        try {
            hook = llmService.completeTextWithTimeout(
                    """
                    Ты пишешь первую строку холодного ЛС в Telegram (outreach).
                    Цель: человек ответил, не заблокировал.
                    Правила: ≤14 слов; конкретика под канал/нишу; без «дорогой»; без ссылок;
                    без спам-триггеров (заработок, крипта, 100%); звучит как живой человек.
                    Верни ТОЛЬКО одну строку-крючок.
                    """,
                    "Шаблон кампании: " + template
                            + "\nПолучатель: @" + username
                            + "\nКанал отправителя: " + channelTitle,
                    15,
                    80);
            hook = hook != null ? hook.trim() : "";
        } catch (Exception ex) {
            log.debug("Outreach personalize skip: {}", ex.getMessage());
        }
        if (hook.isBlank()) {
            return template.replace("{username}", "@" + username).replace("{name}", "@" + username);
        }
        return hook + "\n\n" + template.replace("{username}", "@" + username);
    }

    static ParsedSource parseSource(String raw) {
        Set<String> usernames = new LinkedHashSet<>();
        String groupLink = null;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (GROUP_LINK.matcher(trimmed).find() && trimmed.toLowerCase(Locale.ROOT).contains("join")) {
                groupLink = trimmed;
                continue;
            }
            if (trimmed.contains("t.me/+") || trimmed.contains("joinchat")) {
                groupLink = trimmed;
                continue;
            }
            Matcher m = USERNAME.matcher(trimmed);
            while (m.find()) {
                usernames.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return new ParsedSource(new ArrayList<>(usernames), groupLink);
    }

    private static String buildName(String scenario, ParsedSource parsed) {
        String label = scenarioLabel(scenario);
        String date = DateTimeFormatter.ofPattern("dd.MM").format(Instant.now().atZone(MSK));
        int n = parsed.usernames().size();
        if (n > 0) {
            return label + " · " + n + " чел. · " + date;
        }
        return label + " · группа · " + date;
    }

    public static String scenarioLabel(String scenario) {
        return switch (scenario != null ? scenario : "INVITE") {
            case "CUSTDEV" -> "Custdev";
            case "OFFER" -> "Оффер";
            default -> "Invite";
        };
    }

    public static String statusLabel(String status) {
        return switch (status != null ? status : "DRAFT") {
            case "RUNNING" -> "🟢 Идёт";
            case "PAUSED" -> "⏸ Пауза";
            case "COMPLETED" -> "✅ Готово";
            case "QUEUED" -> "⏳ В очереди";
            default -> "📝 Черновик";
        };
    }

    public record ParsedSource(List<String> usernames, String groupLink) {
    }

    private static String monthKey() {
        return DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.now().atZone(MSK));
    }
}
