package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.entitlement.AssistantQuotaService;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.outreach.OutreachCampaignService;
import org.example.pulse_ai.domain.radar.AdPlacementMatchService;
import org.example.pulse_ai.domain.radar.AdPlacementMatchService.MatchResult;
import org.example.pulse_ai.domain.radar.AdPlacementMatchService.ScoredPlacement;
import org.example.pulse_ai.domain.scout.GroupMemberParseService;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachProspectEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AdPlacementRepository;
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
    private final AssistantQuotaService assistantQuotaService;
    private final EntitlementService entitlementService;
    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final AdPlacementMatchService matchService;
    private final AdPlacementRepository placementRepository;

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
        sb.append("1) Подберём каналы вашей темы\n");
        sb.append("2) Парсинг участников → живые @username\n");
        sb.append("3) Запуск — ЛС уходят постепенно, не пачкой\n\n");
        sb.append(dm.counterLine()).append('\n');
        sb.append("Парсинг: <b>").append(parse.remaining()).append("</b> ост.\n");
        if (campaigns.isEmpty()) {
            sb.append("\nКампаний нет. Начните с парсинга или новой кампании.");
        } else {
            sb.append("\n<b>Кампании:</b>\n");
            for (OutreachCampaignEntity c : campaigns.stream().limit(5).toList()) {
                long pending = campaignService.prospects(c.getId()).stream()
                        .filter(p -> "PENDING".equals(p.getStatus())).count();
                long replied = campaignService.prospects(c.getId()).stream()
                        .filter(p -> "REPLIED".equals(p.getStatus())).count();
                sb.append("• ").append(OutreachCampaignService.statusLabel(c.getStatus()))
                        .append(" #").append(c.getId()).append(" ")
                        .append(TgHtml.esc(c.getName()))
                        .append(" · очередь ").append(pending)
                        .append(" · ответили ").append(replied).append("\n");
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
                        + "Пришлите получателей одним сообщением:\n"
                        + "• ссылку на группу <code>t.me/…</code> — соберём живых\n"
                        + "• или @username, каждый с новой строки\n\n"
                        + "Пример: <code>https://t.me/joinchat/…</code>",
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
            if (campaign.getSourceRef() != null && campaignService.prospects(campaign.getId()).isEmpty()) {
                if (assistantQuotaService.tryConsumeParse(user.getId())) {
                    groupParseService.queueParse(user.getId(), campaign.getId(), campaign.getSourceRef());
                }
            }
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
        AssistantQuotaService.DmQuotaSnapshot dm = assistantQuotaService.dmQuota(user.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("📤 <b>Кампания #").append(c.getId()).append("</b>\n");
        sb.append(OutreachCampaignService.statusLabel(c.getStatus()))
                .append(" · ").append(OutreachCampaignService.scenarioLabel(c.getScenario())).append("\n\n");
        sb.append(dm.counterLine()).append('\n');
        sb.append("Очередь: <b>").append(pending).append("</b> · отправлено: <b>")
                .append(sent).append("</b> · ответили: <b>").append(replied).append("</b>\n");
        if (c.getSourceRef() != null) {
            sb.append("Группа: ").append(TgHtml.esc(c.getSourceRef())).append('\n');
        }
        sb.append('\n');
        if (pending == 0 && c.getSourceRef() != null) {
            sb.append("👉 Дальше: <b>парсинг группы</b> — соберём живых с @username.\n\n");
        } else if (pending > 0 && !"RUNNING".equals(c.getStatus())) {
            sb.append("👉 Дальше: <b>запустить</b> — ЛС уходят постепенно.\n\n");
        } else if ("RUNNING".equals(c.getStatus())) {
            sb.append("Идёт. Ответы появятся здесь и в «Лидах».\n\n");
        }
        sb.append("<b>Шаблон:</b>\n").append(TgHtml.esc(c.getMessageTemplate())).append("\n");
        if (!prospects.isEmpty()) {
            sb.append("\n<b>Пример текста:</b>\n");
            OutreachProspectEntity sample = prospects.get(0);
            String preview = sample.getPersonalizedText() != null
                    ? sample.getPersonalizedText()
                    : c.getMessageTemplate();
            sb.append("<i>").append(TgHtml.esc(preview.length() > 300 ? preview.substring(0, 297) + "…" : preview))
                    .append("</i>");
        }
        editOrSend(chatId, messageId, sb.toString(),
                keyboards.outreachCampaignInline(c, pending, replied, dm.remaining()));
    }

    public void showReplies(long chatId, int messageId, UserEntity user, long campaignId) {
        Optional<OutreachCampaignEntity> opt = campaignService.getCampaign(user.getId(), campaignId);
        if (opt.isEmpty()) {
            messageSender.sendTextSafe(chatId, "Кампания не найдена.");
            return;
        }
        List<OutreachProspectEntity> replies = campaignService.repliedProspects(campaignId);
        StringBuilder sb = new StringBuilder();
        sb.append("💬 <b>Ответы · кампания #").append(campaignId).append("</b>\n\n");
        if (replies.isEmpty()) {
            sb.append("Пока никто не ответил. Когда ответят — статус REPLIED и лид в CRM.");
        } else {
            for (OutreachProspectEntity p : replies) {
                sb.append("• @").append(TgHtml.esc(p.getUsername() != null ? p.getUsername() : "?"))
                        .append('\n');
            }
            sb.append("\nДожим — в «🔥 Лиды» (категория ответа на рассылку).");
        }
        editOrSend(chatId, messageId, sb.toString(), keyboards.outreachParseDoneInline(campaignId));
    }

    public void startCampaign(long chatId, int messageId, UserEntity user, long campaignId) {
        try {
            campaignService.startCampaign(user.getId(), campaignId);
            String channelTitle = userService.findActiveChannel(user).map(ChannelEntity::getTitle).orElse("канал");
            campaignService.personalizePending(campaignId, channelTitle);
            showCampaign(chatId, messageId, user, campaignId);
        } catch (IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        }
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
        if (callbackData.equals(CallbackData.AGENT_PARSE_LINK)) {
            promptOwnParseLink(chatId, messageId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.AGENT_PARSE_SRC)) {
            long placementId = Long.parseLong(callbackData.substring(CallbackData.AGENT_PARSE_SRC.length()));
            parseFromPlacement(chatId, messageId, user, placementId);
            return;
        }
        showParseSuggest(chatId, messageId, user);
    }

    public void showParseHub(long chatId, int messageId, UserEntity user) {
        showParseSuggest(chatId, messageId, user);
    }

    private void showParseSuggest(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            editOrSend(chatId, messageId,
                    "🔍 <b>Парсинг ЦА</b>\n\n"
                            + "Сначала разберите свой канал — по постам поймём, где искать людей.",
                    keyboards.agentBackInline());
            return;
        }
        AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());
        editOrSend(chatId, messageId,
                "🔍 <b>Парсинг ЦА</b>\n\n⏳ Ищу каналы, откуда брать аудиторию…",
                null);

        MatchResult result = matchService.matchForChannel(user, channelOpt.get());
        if (result.isEmpty()) {
            String detail = result.emptyMessage() != null ? result.emptyMessage()
                    : "Кандидатов нет. Можно прислать свою ссылку.";
            editOrSend(chatId, messageId,
                    "🔍 <b>Парсинг ЦА</b>\n\n" + detail,
                    keyboards.parseSuggestInline(List.of()));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>Откуда парсить</b>\n");
        if (result.audienceLine() != null && !result.audienceLine().isBlank()) {
            sb.append("<i>").append(TgHtml.esc(result.audienceLine())).append("</i>\n\n");
        }
        sb.append("Каналы вашей темы. Нажмите — соберём участников в рассылку.\n\n");
        int i = 1;
        for (ScoredPlacement sp : result.placements()) {
            AdPlacementEntity p = sp.placement();
            sb.append(i++).append(". <b>@").append(TgHtml.esc(p.getTargetUsername())).append("</b>");
            if (p.getTargetTitle() != null) {
                sb.append(" — ").append(TgHtml.esc(shorten(p.getTargetTitle(), 40)));
            }
            sb.append('\n');
            if (sp.subscribersHint() > 0) {
                sb.append("   👥 ~").append(formatNum(sp.subscribersHint()));
            }
            if (p.getAvgViews() != null && p.getAvgViews() > 0) {
                if (sp.subscribersHint() > 0) {
                    sb.append(" · ");
                } else {
                    sb.append("   ");
                }
                sb.append("👁 ~").append(formatNum(p.getAvgViews()));
            }
            sb.append("\n\n");
        }
        sb.append("Парсинг: <b>").append(parse.remaining()).append("</b> ост.");
        editOrSend(chatId, messageId, sb.toString().trim(),
                keyboards.parseSuggestInline(result.placements().stream().map(ScoredPlacement::placement).toList()));
    }

    private void promptOwnParseLink(long chatId, int messageId, UserEntity user) {
        AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());
        editOrSend(chatId, messageId,
                "🔍 <b>Своя ссылка</b>\n\n"
                        + "Пришлите группу или канал: <code>t.me/…</code> / @chat\n\n"
                        + "Парсинг: <b>" + parse.remaining() + "</b> ост.",
                keyboards.agentBackInline());
        sessionService.getOrCreate(chatId).setState(BotState.AUDIENCE_PARSE_INPUT);
    }

    private void parseFromPlacement(long chatId, int messageId, UserEntity user, long placementId) {
        AdPlacementEntity p = placementRepository.findByIdAndUserId(placementId, user.getId()).orElse(null);
        if (p == null || p.getTargetUsername() == null || p.getTargetUsername().isBlank()) {
            messageSender.sendTextSafe(chatId, "Канал не найден. Запустите поиск ещё раз.");
            return;
        }
        handleParseLinkInput(chatId, user, "https://t.me/" + p.getTargetUsername());
    }

    public void handleParseLinkInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        String link = raw != null ? raw.trim() : "";
        if (link.startsWith("@")) {
            link = "https://t.me/" + link.substring(1);
        }
        if (link.isBlank() || !(link.contains("t.me") || link.contains("telegram.me"))) {
            messageSender.sendTextSafe(chatId, "Нужна ссылка на группу/канал (t.me/… или @chat).");
            return;
        }
        if (!assistantQuotaService.tryConsumeParse(user.getId())) {
            messageSender.sendTextWithInlineSafe(chatId,
                    "🔒 Квота парсинга исчерпана. Апгрейд — в «💳 Тарифы».",
                    keyboards.backToMainInline());
            return;
        }
        Long ownerChannelId = userService.findActiveChannel(user).map(ChannelEntity::getId).orElse(null);
        try {
            OutreachCampaignEntity campaign = campaignService.createFromGroupLink(
                    user, ownerChannelId, link, "INVITE");
            groupParseService.queueParse(user.getId(), campaign.getId(), link);
            showCampaign(chatId, 0, user, campaign.getId());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        }
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
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH_REPLIES)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_OUTREACH_REPLIES.length()));
            showReplies(chatId, messageId, user, id);
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

    private static String formatNum(int n) {
        return String.format("%,d", n).replace(',', ' ');
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
