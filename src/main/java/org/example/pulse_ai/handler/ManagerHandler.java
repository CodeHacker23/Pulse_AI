package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.channel.DiscussionLinkService;
import org.example.pulse_ai.domain.entitlement.AssistantQuotaService;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.lead.LeadStatus;
import org.example.pulse_ai.domain.lead.SalesLearningService;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.HotLeadEntity;
import org.example.pulse_ai.persistence.entity.SalesLearningEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.HotLeadRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Экран мини-агента админа в комментариях: статус, FAQ, книга возражений,
 * выводы после won/lost, список лидов с CRM и ответом в один клик.
 */
@Component
@RequiredArgsConstructor
public class ManagerHandler {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final UserService userService;
    private final ChannelRepository channelRepository;
    private final HotLeadRepository hotLeadRepository;
    private final EntitlementService entitlementService;
    private final AssistantQuotaService assistantQuotaService;
    private final SalesLearningService salesLearningService;
    private final DiscussionLinkService discussionLinkService;
    private final TelegramMessageSender messageSender;
    private final UserSessionService sessionService;
    private final KeyboardFactory keyboards;
    private final AdRadarHandler adRadarHandler;
    private final AdDealHandler adDealHandler;
    private final OutreachHandler outreachHandler;
    private final ScoutAdminHandler scoutAdminHandler;

    public void open(long chatId, UserEntity user) {
        showAgent(chatId, 0, user);
    }

    /** Хаб роста с главного меню — без скаутов в UI для обычных пользователей. */
    public void openGrowth(long chatId, UserEntity user) {
        showGrowthHub(chatId, 0, user);
    }

    public void handle(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        messageSender.answerCallback(callbackQueryId);
        if (callbackData.equals(CallbackData.AGENT_TOGGLE)) {
            toggle(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_SALES)) {
            showSalesHub(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_GROWTH)) {
            showGrowthHub(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_SETTINGS)) {
            showSettingsHub(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_LEADS)) {
            showLeads(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_HELP)) {
            showHelp(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_FAQ)) {
            showFaq(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_FAQ_SET)) {
            promptFaq(chatId, user);
        } else if (callbackData.equals(CallbackData.AGENT_OBJECTIONS)) {
            showObjections(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_OBJECTIONS_SET)) {
            promptObjections(chatId, user);
        } else if (callbackData.equals(CallbackData.AGENT_LEARNINGS)) {
            showLearnings(chatId, messageId, user);
        } else if (callbackData.startsWith(CallbackData.AGENT_RADAR)) {
            adRadarHandler.handle(chatId, messageId, callbackData, user);
        } else if (callbackData.startsWith(CallbackData.AGENT_DEAL)) {
            adDealHandler.handle(chatId, messageId, callbackData, user);
        } else if (callbackData.startsWith(CallbackData.PREFIX_AGENT + "scout")) {
            scoutAdminHandler.handle(chatId, messageId, user, callbackData);
        } else if (callbackData.startsWith(CallbackData.AGENT_OUTREACH)
                || callbackData.equals(CallbackData.AGENT_OUTREACH)) {
            outreachHandler.handle(chatId, messageId, callbackData, user);
        } else if (callbackData.equals(CallbackData.AGENT_PARSE)
                || callbackData.startsWith(CallbackData.AGENT_PARSE)) {
            outreachHandler.handleParse(chatId, messageId, callbackData, user);
        } else if (callbackData.startsWith(CallbackData.AGENT_REPLY_EDIT)) {
            promptCustomReply(chatId, user, parseId(callbackData, CallbackData.AGENT_REPLY_EDIT));
        } else if (callbackData.startsWith(CallbackData.AGENT_REPLY)) {
            sendSuggestedReply(chatId, messageId, user, parseId(callbackData, CallbackData.AGENT_REPLY));
        } else if (callbackData.startsWith(CallbackData.AGENT_STATUS)) {
            changeStatus(chatId, messageId, user, callbackData);
        } else {
            showAgent(chatId, messageId, user);
        }
    }

    private void showAgent(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            send(chatId, messageId,
                    "🧑\u200d💼 <b>Pulse Ассистент</b>\n\n"
                            + "Сначала подключите канал (пришлите ссылку или перешлите пост из своего канала, "
                            + "где бот админ). Потом ассистент начнёт ловить лидов в комментариях.",
                    keyboards.backToMainInline());
            return;
        }

        ChannelEntity channel = channelOpt.get();
        if (channel.getLinkedDiscussionChatId() == null) {
            discussionLinkService.resolveDiscussionChatId(channel);
            channel = channelRepository.findById(channel.getId()).orElse(channel);
        }
        boolean subscribed = entitlementService.hasAccess(user.getId(), PerkType.MANAGER);
        boolean linked = channel.getLinkedDiscussionChatId() != null;
        boolean enabled = channel.isLeadAgentEnabled();
        boolean hasFaq = channel.getSalesFaq() != null && !channel.getSalesFaq().isBlank();
        boolean hasObj = channel.getSalesObjections() != null && !channel.getSalesObjections().isBlank();
        long leadCount = hotLeadRepository.countByOwnerUserId(user.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("🧑\u200d💼 <b>Pulse Ассистент</b>\n");
        sb.append("Канал: «").append(TgHtml.esc(channel.getTitle())).append("»\n\n");
        sb.append("Следит за комментариями и ловит горячие лиды — с черновиком ответа под ваш оффер.\n\n");

        sb.append("<b>Статус</b>\n");
        if (subscribed) {
            AssistantQuotaService.DmQuotaSnapshot dm = assistantQuotaService.dmQuota(user.getId());
            AssistantQuotaService.ParseQuotaSnapshot parse = assistantQuotaService.parseQuota(user.getId());
            sb.append("• Доступ: ✅ активен");
            if (dm.tierName() != null) {
                sb.append(" · ").append(TgHtml.esc(dm.tierName()));
            }
            sb.append('\n');
            sb.append("• ").append(dm.counterLine()).append('\n');
            sb.append("• Парсинг: <b>").append(parse.remaining()).append("</b> ост.\n");
        } else {
            sb.append("• Доступ: 🔒 нужна подписка Ассистент (от 3990 ₽)\n");
        }
        sb.append(linked
                ? "• Комментарии: ✅ группа обсуждений подключена\n"
                : "• Комментарии: ⚠️ группа не найдена — нужна настройка\n");
        sb.append("• В комментариях: ").append(enabled ? "🟢 включён" : "⚪️ выключен").append('\n');
        sb.append("• Профиль компании: ").append(hasFaq ? "✅" : "⚠️").append(" · возражения: ")
                .append(hasObj ? "✅" : "⚪️").append('\n');
        sb.append("• Лидов: <b>").append(leadCount).append("</b>\n");

        if (!subscribed) {
            sb.append("\n👉 «💳 Тарифы» — один платёж: лиды + ЛС + парсинг в квоте.");
        } else if (!linked) {
            sb.append("\n👉 Раздел «⚙️ Настройка» → как подключить комментарии.");
        }

        send(chatId, messageId, sb.toString(), keyboards.agentInline(enabled, subscribed, leadCount));
    }

    private void showSalesHub(long chatId, int messageId, UserEntity user) {
        long leadCount = hotLeadRepository.countByOwnerUserId(user.getId());
        send(chatId, messageId,
                "🔥 <b>Лиды и продажи</b>\n\n"
                        + "• <b>Лиды</b> — CRM по комментариям\n"
                        + "• <b>Рассылка</b> — исходящие ЛС\n"
                        + "• <b>Парсинг ЦА</b> — участники группы, отсев мёртвых/накрутки\n"
                        + "• Профиль, возражения, выводы",
                keyboards.agentSalesInline(leadCount));
    }

    private void showGrowthHub(long chatId, int messageId, UserEntity user) {
        send(chatId, messageId,
                "📈 <b>Рост</b>\n\n"
                        + "• <b>Площадки для рекламы</b> — каналы ниши, проверка, креатив\n"
                        + "• <b>Аналитика+</b> — глубокие метрики (CONTENT+)",
                keyboards.agentGrowthInline());
    }

    private void showSettingsHub(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        boolean subscribed = entitlementService.hasAccess(user.getId(), PerkType.MANAGER);
        boolean enabled = channelOpt.map(ChannelEntity::isLeadAgentEnabled).orElse(false);
        boolean linked = channelOpt.map(c -> c.getLinkedDiscussionChatId() != null).orElse(false);
        String tip = linked
                ? "✅ Группа обсуждений найдена — можно включать ассистента."
                : """
                Чтобы ловить лиды из комментариев:
                1) В канале включите «Обсуждение»
                2) Добавьте бота админом в эту группу
                3) Вернитесь и включите ассистента""";
        send(chatId, messageId,
                "⚙️ <b>Настройка ассистента</b>\n\n" + tip,
                keyboards.agentSettingsInline(enabled, subscribed));
    }

    private void toggle(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            showAgent(chatId, messageId, user);
            return;
        }
        if (!entitlementService.hasAccess(user.getId(), PerkType.MANAGER)) {
            send(chatId, messageId, org.example.pulse_ai.text.SalesCopy.assistantPaywall(),
                    keyboards.backToMainInline());
            return;
        }
        ChannelEntity channel = channelOpt.get();
        channel.setLeadAgentEnabled(!channel.isLeadAgentEnabled());
        channelRepository.save(channel);
        showAgent(chatId, messageId, user);
    }

    private void showLeads(long chatId, int messageId, UserEntity user) {
        List<HotLeadEntity> leads = hotLeadRepository.findTop10ByOwnerUserIdOrderByCreatedAtDesc(user.getId());
        long total = hotLeadRepository.countByOwnerUserId(user.getId());
        long inProgress = hotLeadRepository.countByOwnerUserIdAndStatus(user.getId(), "IN_PROGRESS");
        long won = hotLeadRepository.countByOwnerUserIdAndStatus(user.getId(), "WON");
        long newLeads = hotLeadRepository.countByOwnerUserIdAndStatus(user.getId(), "NEW");

        StringBuilder sb = new StringBuilder("🔥 <b>Горячие лиды</b>\n\n");
        sb.append("📊 Воронка: всего <b>").append(total).append("</b> · 🆕 ")
                .append(newLeads).append(" · 📞 ").append(inProgress).append(" · ✅ ").append(won).append("\n\n");

        if (leads.isEmpty()) {
            sb.append("Пока пусто. Как только под постом кто-то спросит цену или захочет купить — "
                    + "лид появится здесь и придёт вам уведомлением с готовым черновиком ответа.");
        } else {
            for (HotLeadEntity lead : leads) {
                String when = HUMAN.format(ZonedDateTime.ofInstant(lead.getCreatedAt(), MSK));
                sb.append(LeadStatus.of(lead.getStatus()).label()).append(" · <b>").append(when).append("</b> · ")
                        .append(mention(lead)).append('\n');
                String text = lead.getCommentText() != null ? lead.getCommentText() : "";
                text = text.replace('\n', ' ').trim();
                if (text.length() > 90) {
                    text = text.substring(0, 87) + "…";
                }
                sb.append("  <i>").append(TgHtml.esc(text)).append("</i>");
                if (lead.getCommentLink() != null) {
                    sb.append(" — <a href=\"").append(TgHtml.esc(lead.getCommentLink())).append("\">открыть</a>");
                }
                sb.append("\n\n");
            }
        }
        send(chatId, messageId, sb.toString().trim(), keyboards.agentBackInline());
    }

    private void sendSuggestedReply(long chatId, int messageId, UserEntity user, Long leadId) {
        HotLeadEntity lead = ownedLead(user, leadId);
        if (lead == null) {
            return;
        }
        if (lead.getSuggestedReply() == null || lead.getSuggestedReply().isBlank()) {
            promptCustomReply(chatId, user, leadId);
            return;
        }
        boolean ok = postReplyToComments(lead, lead.getSuggestedReply());
        finishReply(chatId, messageId, lead, ok);
    }

    private void promptCustomReply(long chatId, UserEntity user, Long leadId) {
        HotLeadEntity lead = ownedLead(user, leadId);
        if (lead == null) {
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setPendingLeadId(leadId);
        session.setState(BotState.AGENT_REPLY_INPUT);
        messageSender.sendText(chatId,
                "✏️ Напишите ваш ответ клиенту одним сообщением — агент опубликует его в комментариях под постом.\n\n"
                        + "Комментарий клиента:\n«" + safeShort(lead.getCommentText()) + "»");
    }

    public void handleCustomReplyInput(long chatId, UserEntity user, String text) {
        UserSession session = sessionService.getOrCreate(chatId);
        Long leadId = session.getPendingLeadId();
        session.setPendingLeadId(null);
        session.setState(BotState.MAIN_MENU);
        HotLeadEntity lead = ownedLead(user, leadId);
        if (lead == null) {
            messageSender.sendText(chatId, "Лид не найден. Откройте «🔥 Лиды» в меню агента.");
            return;
        }
        boolean ok = postReplyToComments(lead, text.trim());
        finishReply(chatId, 0, lead, ok);
    }

    private void finishReply(long chatId, int messageId, HotLeadEntity lead, boolean ok) {
        if (ok) {
            lead.setStatus("IN_PROGRESS");
            hotLeadRepository.save(lead);
        }
        String text = ok
                ? "✅ <b>Ответ отправлен в комментарии.</b>\nСтатус лида: 📞 В работе. "
                + "Отметьте результат, когда закроете сделку."
                : "⚠️ Не удалось отправить ответ в комментарии. Убедитесь, что бот — "
                + "администратор в группе обсуждений канала, и попробуйте ещё раз.";
        InlineKeyboardMarkup kb = keyboards.leadNotificationInline(lead.getId(), false, lead.getCommentLink());
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, kb);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, kb);
        }
    }

    private void changeStatus(long chatId, int messageId, UserEntity user, String callbackData) {
        String rest = callbackData.substring(CallbackData.AGENT_STATUS.length());
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return;
        }
        LeadStatus status = LeadStatus.of(rest.substring(0, colon));
        Long leadId = parseLong(rest.substring(colon + 1));
        HotLeadEntity lead = ownedLead(user, leadId);
        if (lead == null) {
            return;
        }
        lead.setStatus(status.name());
        hotLeadRepository.save(lead);

        String learningNote = "";
        if (status == LeadStatus.WON || status == LeadStatus.LOST) {
            try {
                SalesLearningEntity learning = salesLearningService.recordOutcome(lead, status.name());
                learningNote = "\n\n📌 <b>Вывод сохранён:</b>\n<i>"
                        + TgHtml.esc(safeShort(learning.getSummary())) + "</i>";
            } catch (Exception ignored) {
                learningNote = "\n\n(Вывод не записался — можно открыть «📌 Выводы» позже.)";
            }
        }

        String text = "Лид от " + mention(lead) + "\nСтатус обновлён: <b>" + status.label() + "</b>" + learningNote;
        InlineKeyboardMarkup kb = keyboards.leadNotificationInline(lead.getId(),
                lead.getSuggestedReply() != null && !lead.getSuggestedReply().isBlank(), lead.getCommentLink());
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, kb);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, kb);
        }
    }

    private boolean postReplyToComments(HotLeadEntity lead, String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        return messageSender.sendReplyToChat(lead.getDiscussionChatId(),
                TgHtml.esc(reply.trim()), lead.getCommentMessageId().intValue());
    }

    private void showFaq(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            showAgent(chatId, messageId, user);
            return;
        }
        String faq = channelOpt.get().getSalesFaq();
        boolean hasFaq = faq != null && !faq.isBlank();
        StringBuilder sb = new StringBuilder("🧠 <b>Профиль компании (рамки для агента)</b>\n\n");
        sb.append("Это <b>единственный источник</b>, по которому агент отвечает клиентам. "
                + "Он берёт факты только отсюда и <b>не выходит за рамки</b> — не выдумывает цены, сроки и обещания. "
                + "Чего здесь нет — агент не обещает, а зовёт клиента в личку.\n\n");
        sb.append("Добавьте: о компании, услуги/товары, цены и условия, правила, "
                + "<b>чего НЕ обещать</b>, контакт для сделки.\n\n");
        if (hasFaq) {
            sb.append("<b>Сейчас загружено:</b>\n<i>").append(TgHtml.esc(faq.trim())).append("</i>");
        } else {
            sb.append("⚠️ Профиль пуст — пока агент отвечает только общими фразами и зовёт в личку "
                    + "(без цен и конкретики). Заполните, чтобы отвечал по делу.");
        }
        send(chatId, messageId, sb.toString(), keyboards.faqInline(hasFaq));
    }

    private void promptFaq(long chatId, UserEntity user) {
        sessionService.getOrCreate(chatId).setState(BotState.AGENT_FAQ_INPUT);
        messageSender.sendText(chatId,
                "🧠 Пришлите одним сообщением профиль компании. Чем точнее — тем точнее ответы. Шаблон:\n\n"
                        + "О компании: кто мы, что делаем\n"
                        + "Услуги/товары: список с кратким описанием\n"
                        + "Цены и условия: 3 900 ₽; доставка СДЭК 1–3 дня от 300 ₽\n"
                        + "Правила: предоплата 50%, возврат 14 дней\n"
                        + "Чего НЕ обещать: без гарантий результата, без скидок сверх прайса\n"
                        + "Контакт для сделки: @username\n\n"
                        + "Агент будет отвечать строго в этих рамках и ничего не додумает.");
    }

    public void handleFaqInput(long chatId, UserEntity user, String text) {
        sessionService.getOrCreate(chatId).setState(BotState.MAIN_MENU);
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            messageSender.sendText(chatId, "Сначала подключите канал.");
            return;
        }
        ChannelEntity channel = channelOpt.get();
        channel.setSalesFaq(text.length() > 4000 ? text.substring(0, 4000) : text);
        channelRepository.save(channel);
        messageSender.sendText(chatId,
                "✅ Профиль компании сохранён. Теперь агент отвечает строго по этим данным и не выходит за рамки.");
        showAgent(chatId, 0, user);
    }

    private void showObjections(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            showAgent(chatId, messageId, user);
            return;
        }
        String book = channelOpt.get().getSalesObjections();
        boolean has = book != null && !book.isBlank();
        StringBuilder sb = new StringBuilder("📘 <b>Книга возражений</b>\n\n");
        sb.append("Рабочие формулировки против «дорого», «подумаю», «у конкурента дешевле». "
                + "Агент подмешивает их в черновики. При ✅ Продаже удачные фразы дописываются сами.\n\n");
        if (has) {
            sb.append("<b>Сейчас:</b>\n<i>").append(TgHtml.esc(book.trim())).append("</i>");
        } else {
            sb.append("Пока пусто. Заполните вручную или закройте пару сделок — система начнёт копить фразы.");
        }
        send(chatId, messageId, sb.toString(), keyboards.objectionsInline(has));
    }

    private void promptObjections(long chatId, UserEntity user) {
        sessionService.getOrCreate(chatId).setState(BotState.AGENT_OBJECTIONS_INPUT);
        messageSender.sendText(chatId,
                "📘 Пришлите книгу возражений одним сообщением. Пример:\n\n"
                        + "• Дорого → сравниваем с Х часов своими силами, без абонентки\n"
                        + "• Подумаю → ок, кину в лс 2 пункта «что обычно спрашивают перед решением»\n"
                        + "• У конкурента дешевле → у нас входит Y, у них — нет\n\n"
                        + "Пишите своими словами — так агент и будет звучать.");
    }

    public void handleObjectionsInput(long chatId, UserEntity user, String text) {
        sessionService.getOrCreate(chatId).setState(BotState.MAIN_MENU);
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            messageSender.sendText(chatId, "Сначала подключите канал.");
            return;
        }
        ChannelEntity channel = channelOpt.get();
        channel.setSalesObjections(text.length() > 4000 ? text.substring(0, 4000) : text);
        channelRepository.save(channel);
        messageSender.sendText(chatId, "✅ Книга возражений сохранена. Черновики станут живее.");
        showAgent(chatId, 0, user);
    }

    private void showLearnings(long chatId, int messageId, UserEntity user) {
        List<SalesLearningEntity> rows = salesLearningService.latestForOwner(user.getId());
        StringBuilder sb = new StringBuilder("📌 <b>Выводы агента</b>\n\n");
        sb.append("После «✅ Продажа» / «❌ Слив» система пишет короткий разбор. "
                + "Вы читаете здесь; агент учитывает последние 5 в ответах по каналу.\n\n");
        if (rows.isEmpty()) {
            sb.append("Пока пусто. Закройте лид статусом продажи или слива — появится первый вывод.");
        } else {
            for (SalesLearningEntity row : rows) {
                String when = HUMAN.format(ZonedDateTime.ofInstant(row.getCreatedAt(), MSK));
                String badge = "WON".equalsIgnoreCase(row.getOutcome()) ? "✅" : "❌";
                sb.append(badge).append(" <b>").append(when).append("</b>\n");
                sb.append("<i>").append(TgHtml.esc(safeShort(row.getSummary()))).append("</i>\n\n");
            }
        }
        send(chatId, messageId, sb.toString().trim(), keyboards.agentBackInline());
    }

    private void showHelp(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        boolean linked = false;
        if (channelOpt.isPresent()) {
            ChannelEntity channel = channelOpt.get();
            discussionLinkService.resolveDiscussionChatId(channel);
            linked = channelRepository.findById(channel.getId())
                    .map(c -> c.getLinkedDiscussionChatId() != null)
                    .orElse(false);
        }

        String text = """
                ❓ <b>Как включить лидов из комментариев</b>

                Комментарии в Telegram живут в группе обсуждений канала. Чтобы агент их видел:

                1. В настройках канала включите «Обсуждение» и создайте/привяжите группу.
                2. Добавьте нашего бота в эту группу <b>администратором</b>.
                3. Готово — после нового поста с комментариями или открытия экрана агента бот подтянет связку сам.

                После этого вернитесь сюда и включите агента. Он присылает горячие лиды
                (цена / покупка / возражение) с коротким черновиком ответа.""";
        if (linked) {
            text += "\n\n✅ Сейчас группа обсуждений уже найдена.";
        }
        send(chatId, messageId, text, keyboards.agentBackInline());
    }

    private HotLeadEntity ownedLead(UserEntity user, Long leadId) {
        if (leadId == null) {
            return null;
        }
        HotLeadEntity lead = hotLeadRepository.findById(leadId).orElse(null);
        if (lead == null || !lead.getOwnerUserId().equals(user.getId())) {
            return null;
        }
        return lead;
    }

    private static Long parseId(String data, String prefix) {
        return parseLong(data.substring(prefix.length()));
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String safeShort(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 197) + "…" : t;
    }

    private void send(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private static String mention(HotLeadEntity lead) {
        if (lead.getCommenterUsername() != null && !lead.getCommenterUsername().isBlank()) {
            return "@" + lead.getCommenterUsername();
        }
        return lead.getCommenterName() != null && !lead.getCommenterName().isBlank()
                ? TgHtml.esc(lead.getCommenterName())
                : "пользователь";
    }
}
