package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseOutreachProperties;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.entitlement.AssistantQuotaService;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.outreach.OutreachCampaignService;
import org.example.pulse_ai.domain.scout.GroupMemberParseService;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.Optional;

/** Pulse P1 — исходящие рассылки в ЛС. */
@Component
@RequiredArgsConstructor
public class OutreachHandler {

    private final OutreachCampaignService campaignService;
    private final GroupMemberParseService groupParseService;
    private final PulseOutreachProperties outreachProperties;
    private final PulseScoutProperties scoutProperties;
    private final ScoutAccountService scoutAccountService;
    private final AssistantQuotaService assistantQuotaService;
    private final EntitlementService entitlementService;
    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void showMenu(long chatId, int messageId, UserEntity user) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.MANAGER)) {
            editOrSend(chatId, messageId, org.example.pulse_ai.text.SalesCopy.assistantPaywall(),
                    keyboards.backToMainInline());
            return;
        }
        List<OutreachCampaignEntity> campaigns = campaignService.listCampaigns(user.getId());
        AssistantQuotaService.DmQuotaSnapshot dm = assistantQuotaService.dmQuota(user.getId());
        AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("📨 <b>Рассылки в ЛС</b>\n\n");
        sb.append("Invite, custdev или оффер — Pulse персонализирует текст и ставит в очередь.\n\n");
        sb.append(dm.counterLine()).append('\n');
        sb.append("Парсинг: <b>").append(parse.remaining()).append("</b> ост.\n");
        if (!outreachProperties.isDispatchEnabled()) {
            sb.append("\n⚠️ <b>Отправка выключена</b> (dispatch-enabled=false) — очередь копится, ЛС не уходят.\n");
        }
        if (campaigns.isEmpty()) {
            sb.append("\nКампаний пока нет. Создайте первую.");
        } else {
            sb.append("\n<b>Последние кампании:</b>\n");
            for (OutreachCampaignEntity c : campaigns.stream().limit(5).toList()) {
                long pending = campaignService.prospects(c.getId()).stream()
                        .filter(p -> "PENDING".equals(p.getStatus())).count();
                sb.append("• #").append(c.getId()).append(" ")
                        .append(OutreachCampaignService.statusLabel(c.getStatus()))
                        .append(" · ").append(TgHtml.esc(c.getName()))
                        .append(" · отправлено ").append(c.getSentCount())
                        .append(" · в очереди ").append(pending).append("\n");
            }
        }
        editOrSend(chatId, messageId, sb.toString(), keyboards.outreachMenuInline(campaigns));
    }

    public void pickScenario(long chatId, int messageId, UserEntity user, String scenario) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setOutreachScenario(scenario);
        session.setOutreachSourceDraft(null);
        session.setState(BotState.OUTREACH_SOURCE_INPUT);
        editOrSend(chatId, messageId,
                "📤 <b>" + OutreachCampaignService.scenarioLabel(scenario) + "</b>\n\n"
                        + "Пришлите <b>получателей</b> одним сообщением:\n"
                        + "• @username — каждый с новой строки\n"
                        + "• или ссылку на группу (<code>t.me/+...</code>) — парсинг при подключении scout\n\n"
                        + "Пример:\n<code>@user1\n@user2</code>",
                keyboards.agentBackInline());
    }

    public void handleSourceInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setOutreachSourceDraft(raw.trim());
        session.setState(BotState.OUTREACH_MESSAGE_INPUT);
        String hint = switch (session.getOutreachScenario() != null ? session.getOutreachScenario() : "INVITE") {
            case "CUSTDEV" -> "Здравствуйте! Провожу короткий опрос (3 вопроса) для улучшения продукта. Удобно ответить?";
            case "OFFER" -> "Привет! Веду канал про {topic}. Есть решение, которое может быть полезно — рассказать в двух словах?";
            default -> "Привет! Приглашаю в канал {channel} — там разбираем {topic}. Буду рад, если зайдёте 🙌";
        };
        messageSender.sendTextWithInlineSafe(chatId,
                "✏️ <b>Текст первого сообщения</b>\n\n"
                        + "Напишите шаблон. Можно использовать <code>{username}</code>.\n\n"
                        + "Пример:\n<i>" + TgHtml.esc(hint) + "</i>",
                keyboards.agentBackInline());
    }

    public void handleMessageInput(long chatId, UserEntity user, String message) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        String source = session.getOutreachSourceDraft();
        String scenario = session.getOutreachScenario() != null ? session.getOutreachScenario() : "INVITE";
        if (source == null || source.isBlank()) {
            messageSender.sendTextSafe(chatId, "Источник получателей потерян. Начните кампанию заново.");
            return;
        }
        Long ownerChannelId = userService.findActiveChannel(user).map(ChannelEntity::getId).orElse(null);
        try {
            OutreachCampaignEntity campaign = campaignService.createCampaign(
                    user, ownerChannelId, scenario, source, message.trim());
            String channelTitle = userService.findActiveChannel(user).map(ChannelEntity::getTitle).orElse("канал");
            campaignService.personalizePending(campaign.getId(), channelTitle);
            showCampaign(chatId, 0, user, campaign.getId());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "Не удалось создать кампанию. Проверьте данные.");
        }
    }

    public void showCampaign(long chatId, int messageId, UserEntity user, long campaignId) {
        Optional<OutreachCampaignEntity> opt = campaignService.getCampaign(user.getId(), campaignId);
        if (opt.isEmpty()) {
            messageSender.sendTextSafe(chatId, "Кампания не найдена.");
            return;
        }
        OutreachCampaignEntity c = opt.get();
        List<OutreachProspectEntity> prospects = campaignService.prospects(campaignId);
        long pending = prospects.stream().filter(p -> "PENDING".equals(p.getStatus())).count();
        long sent = prospects.stream().filter(p -> "SENT".equals(p.getStatus())).count();
        long replied = prospects.stream().filter(p -> "REPLIED".equals(p.getStatus())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("📤 <b>Кампания #").append(c.getId()).append("</b>\n");
        sb.append(OutreachCampaignService.statusLabel(c.getStatus()))
                .append(" · ").append(OutreachCampaignService.scenarioLabel(c.getScenario())).append("\n\n");
        sb.append("Очередь: <b>").append(pending).append("</b> · отправлено: <b>")
                .append(sent).append("</b> · ответили: <b>").append(replied).append("</b>\n\n");
        sb.append("<b>Шаблон:</b>\n").append(TgHtml.esc(c.getMessageTemplate())).append("\n");
        if (c.getSourceRef() != null) {
            sb.append("\n<b>Группа:</b> ").append(TgHtml.esc(c.getSourceRef()));
            if (pending == 0 && prospects.isEmpty()) {
                sb.append("\n<i>Парсинг участников — при подключении scout-аккаунтов.</i>");
            }
        }
        if (!prospects.isEmpty()) {
            sb.append("\n\n<b>Пример текста:</b>\n");
            OutreachProspectEntity sample = prospects.get(0);
            String preview = sample.getPersonalizedText() != null
                    ? sample.getPersonalizedText()
                    : c.getMessageTemplate();
            sb.append("<i>").append(TgHtml.esc(preview.length() > 300 ? preview.substring(0, 297) + "…" : preview))
                    .append("</i>");
        }
        editOrSend(chatId, messageId, sb.toString(), keyboards.outreachCampaignInline(c));
    }

    public void startCampaign(long chatId, int messageId, UserEntity user, long campaignId) {
        try {
            OutreachCampaignEntity c = campaignService.startCampaign(user.getId(), campaignId);
            String channelTitle = userService.findActiveChannel(user).map(ChannelEntity::getTitle).orElse("канал");
            campaignService.personalizePending(campaignId, channelTitle);
            String note = buildStartNote(campaignId);
            messageSender.sendTextSafe(chatId, note);
            showCampaign(chatId, messageId, user, campaignId);
        } catch (IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        }
    }

    private String buildStartNote(long campaignId) {
        if (!outreachProperties.isDispatchEnabled()) {
            return "⏸ Кампания #" + campaignId + " «идёт», но <b>dispatch выключен</b> — ЛС не уходят.\n"
                    + "Включи: <code>pulse.outreach.dispatch-enabled: true</code>";
        }
        if (!scoutProperties.sidecarConfigured()) {
            return "⏸ Кампания #" + campaignId + " в очереди, но <b>sidecar не настроен</b> "
                    + "(pulse.scout.sidecar-url).";
        }
        if (scoutAccountService.pickOutreachAccount().isEmpty()) {
            return "⏸ Кампания #" + campaignId + " в очереди, но <b>нет ACTIVE SENDER</b> в скаутах.\n"
                    + "Добавь аккаунт типа SENDER/OUTREACH в /admin или /scout.";
        }
        return "🟢 Кампания #" + campaignId + " запущена. Scout шлёт ЛС из очереди (~раз в 1–2 мин).";
    }

    public void pauseCampaign(long chatId, int messageId, UserEntity user, long campaignId) {
        campaignService.pauseCampaign(user.getId(), campaignId);
        showCampaign(chatId, messageId, user, campaignId);
    }

    public void promptImport(long chatId, UserEntity user, long campaignId) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setOutreachCampaignId(campaignId);
        session.setState(BotState.OUTREACH_IMPORT_INPUT);
        messageSender.sendTextWithInlineSafe(chatId,
                "➕ Добавьте @username (каждый с новой строки):",
                keyboards.agentBackInline());
    }

    public void handleImportInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        Long campaignId = session.getOutreachCampaignId();
        if (campaignId == null) {
            return;
        }
        try {
            int added = campaignService.importUsernames(user.getId(), campaignId, raw);
            messageSender.sendTextSafe(chatId, "✅ Добавлено: " + added + " получателей.");
            showCampaign(chatId, 0, user, campaignId);
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        }
    }

    public void parseGroup(long chatId, int messageId, UserEntity user, long campaignId) {
        Optional<OutreachCampaignEntity> opt = campaignService.getCampaign(user.getId(), campaignId);
        if (opt.isEmpty() || opt.get().getSourceRef() == null) {
            messageSender.sendTextSafe(chatId, "У кампании нет ссылки на группу.");
            return;
        }
        if (!assistantQuotaService.tryConsumeParse(user.getId())) {
            messageSender.sendTextSafe(chatId,
                    "🔒 Квота парсинга своих ссылок исчерпана в этом месяце.\n"
                            + "Апгрейд тарифа или новый месяц — в «💳 Тарифы».");
            return;
        }
        groupParseService.queueParse(user.getId(), campaignId, opt.get().getSourceRef());
        AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());
        messageSender.sendTextSafe(chatId,
                "🔍 Парсинг группы в очереди. Осталось парсингов: <b>" + parse.remaining() + "</b>.");
        showCampaign(chatId, messageId, user, campaignId);
    }

    public void handleParse(long chatId, int messageId, String callbackData, UserEntity user) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.MANAGER)) {
            editOrSend(chatId, messageId, org.example.pulse_ai.text.SalesCopy.assistantPaywall(),
                    keyboards.backToMainInline());
            return;
        }
        showParseHub(chatId, messageId, user);
    }

    public void showParseHub(long chatId, int messageId, UserEntity user) {
        AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());
        editOrSend(chatId, messageId,
                "🔍 <b>Парсинг ЦА</b>\n\n"
                        + "Скаут заходит в группу/канал по ссылке, снимает участников и отсеивает "
                        + "мёртвые, накрученные и ботов — остаются живые с @username.\n\n"
                        + "Квота: <b>" + parse.remaining() + "</b> ост.\n\n"
                        + "Пришлите ссылку: <code>t.me/+...</code> / <code>t.me/joinchat/...</code> / публичный @chat",
                keyboards.agentBackInline());
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.AUDIENCE_PARSE_INPUT);
    }

    public void handleParseLinkInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        String link = raw != null ? raw.trim() : "";
        if (link.isBlank() || !(link.contains("t.me") || link.startsWith("@"))) {
            messageSender.sendTextSafe(chatId, "Нужна ссылка на группу/канал (t.me/… или @username).");
            return;
        }
        if (!assistantQuotaService.tryConsumeParse(user.getId())) {
            messageSender.sendTextSafe(chatId,
                    "🔒 Квота парсинга исчерпана. Апгрейд тарифа — в «💳 Тарифы» (парсинг уже внутри подписки).");
            return;
        }
        groupParseService.queueParse(user.getId(), null, link);
        AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());
        messageSender.sendTextWithInlineSafe(chatId,
                "🔍 Парсинг в очереди.\n"
                        + "Скаут зайдёт, снимет участников и отсеет мёртвых.\n"
                        + "Осталось парсингов: <b>" + parse.remaining() + "</b>.",
                keyboards.agentBackInline());
    }

    public void handle(long chatId, int messageId, String callbackData, UserEntity user) {
        if (callbackData.equals(CallbackData.AGENT_OUTREACH)) {
            showMenu(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_OUTREACH_NEW)) {
            editOrSend(chatId, messageId,
                    "📤 <b>Новая кампания</b>\n\nВыберите сценарий:",
                    keyboards.outreachScenarioInline());
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_SCENARIO)) {
            String scenario = callbackData.substring(CallbackData.AGENT_OUTREACH_SCENARIO.length());
            pickScenario(chatId, messageId, user, scenario);
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_VIEW)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_OUTREACH_VIEW.length()));
            showCampaign(chatId, messageId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_START)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_OUTREACH_START.length()));
            startCampaign(chatId, messageId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_PAUSE)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_OUTREACH_PAUSE.length()));
            pauseCampaign(chatId, messageId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_IMPORT)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_OUTREACH_IMPORT.length()));
            promptImport(chatId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_PARSE)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_OUTREACH_PARSE.length()));
            parseGroup(chatId, messageId, user, id);
        } else {
            showMenu(chatId, messageId, user);
        }
    }

    private void editOrSend(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }
}
