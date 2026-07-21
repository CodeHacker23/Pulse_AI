package org.example.pulse_ai.domain.lead;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.channel.DiscussionLinkService;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.HotLeadEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.HotLeadRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

/**
 * Мини-агент админа в комментариях: слушает группу обсуждений канала,
 * ловит горячие лиды (интерес к цене/покупке) и уведомляет владельца канала.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentLeadAgent {

    private final ChannelRepository channelRepository;
    private final HotLeadRepository hotLeadRepository;
    private final UserRepository userRepository;
    private final LeadDetectionService leadDetectionService;
    private final SalesLearningService salesLearningService;
    private final DiscussionLinkService discussionLinkService;
    private final EntitlementService entitlementService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void handleGroupMessage(Message message) {
        if (message == null || message.getChatId() == null) {
            return;
        }
        long groupId = message.getChatId();

        // 1) Авто-форвард поста канала в группу обсуждений — запоминаем связку канал↔группа.
        Chat forwardFrom = message.getForwardFromChat();
        if (forwardFrom != null && "channel".equals(forwardFrom.getType())) {
            learnLinkage(forwardFrom.getId(), groupId);
            return;
        }

        // 2) Обычный комментарий пользователя.
        ChannelEntity channel = channelRepository.findByLinkedDiscussionChatId(groupId).orElse(null);
        if (channel == null || !channel.isLeadAgentEnabled()) {
            return;
        }
        User from = message.getFrom();
        if (from == null || Boolean.TRUE.equals(from.getIsBot())) {
            return;
        }
        String text = message.getText() != null ? message.getText() : message.getCaption();
        if (text == null || text.isBlank()) {
            return;
        }
        long commentMsgId = message.getMessageId();
        if (hotLeadRepository.existsByDiscussionChatIdAndCommentMessageId(groupId, commentMsgId)) {
            return;
        }
        if (!entitlementService.hasAccess(channel.getOwnerUserId(), PerkType.MANAGER)) {
            return;
        }

        LeadDetectionService.LeadVerdict verdict = leadDetectionService.classify(text);
        if (!verdict.hot()) {
            return;
        }

        String learnings = salesLearningService.recentContextForChannel(channel.getId());
        String suggested = leadDetectionService.suggestReply(
                text, channel.getTitle(), channel.getSalesFaq(),
                channel.getSalesObjections(), learnings);
        HotLeadEntity lead = saveLead(channel, message, from, text, verdict, groupId, commentMsgId, suggested);
        notifyOwner(channel, lead);
    }

    private void learnLinkage(Long channelTelegramId, long groupId) {
        discussionLinkService.rememberLinkage(channelTelegramId, groupId);
    }

    private HotLeadEntity saveLead(
            ChannelEntity channel, Message message, User from, String text,
            LeadDetectionService.LeadVerdict verdict, long groupId, long commentMsgId, String suggestedReply) {
        HotLeadEntity lead = new HotLeadEntity();
        lead.setChannelId(channel.getId());
        lead.setOwnerUserId(channel.getOwnerUserId());
        lead.setDiscussionChatId(groupId);
        lead.setCommentMessageId(commentMsgId);
        lead.setCommenterUserId(from.getId());
        lead.setCommenterUsername(from.getUserName());
        lead.setCommenterName(fullName(from));
        lead.setCommentText(text.length() > 1000 ? text.substring(0, 1000) : text);
        lead.setCategory(verdict.category());
        lead.setReason(verdict.reason());
        lead.setCommentLink(buildCommentLink(groupId, commentMsgId));
        lead.setStatus("NEW");
        lead.setSuggestedReply(suggestedReply);
        return hotLeadRepository.save(lead);
    }

    private void notifyOwner(ChannelEntity channel, HotLeadEntity lead) {
        UserEntity owner = userRepository.findById(channel.getOwnerUserId()).orElse(null);
        if (owner == null || owner.getTelegramId() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔥 <b>Горячий лид в комментариях</b>\n");
        sb.append("Канал: «").append(TgHtml.esc(channel.getTitle())).append("»\n\n");
        sb.append("<b>").append(TgHtml.esc(categoryLabel(lead.getCategory()))).append("</b>\n");
        sb.append("От: ").append(commenterMention(lead)).append("\n\n");
        sb.append("💬 <i>").append(TgHtml.esc(lead.getCommentText())).append("</i>\n");
        if (lead.getReason() != null && !lead.getReason().isBlank()) {
            sb.append("\n🧠 ").append(TgHtml.esc(lead.getReason()));
        }
        boolean hasReply = lead.getSuggestedReply() != null && !lead.getSuggestedReply().isBlank();
        if (hasReply) {
            sb.append("\n\n✍️ <b>Черновик ответа:</b>\n<i>")
                    .append(TgHtml.esc(lead.getSuggestedReply())).append("</i>\n\n");
            sb.append("Нажмите «✅ Отправить ответ» — и агент сам опубликует его в комментариях.");
        } else {
            sb.append("\n\n➡️ Откройте комментарии под постом и ответьте этому человеку.");
        }
        messageSender.sendTextWithInlineSafe(owner.getTelegramId(), sb.toString(),
                keyboards.leadNotificationInline(lead.getId(), hasReply, lead.getCommentLink()));
        lead.setNotified(true);
        hotLeadRepository.save(lead);
    }

    private static String categoryLabel(String category) {
        return switch (category == null ? "" : category) {
            case "objection" -> "⚡ Возражение (цена/сравнение)";
            case "price" -> "💰 Спрашивает цену";
            case "buy" -> "🛒 Готов купить";
            case "contact" -> "📩 Просит контакт";
            default -> "🔥 Интерес к покупке";
        };
    }

    private static String commenterMention(HotLeadEntity lead) {
        if (lead.getCommenterUsername() != null && !lead.getCommenterUsername().isBlank()) {
            return "@" + lead.getCommenterUsername();
        }
        String name = lead.getCommenterName();
        return name != null && !name.isBlank() ? TgHtml.esc(name) : "пользователь";
    }

    private static String fullName(User user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        return (first + " " + last).trim();
    }

    private static String buildCommentLink(long groupId, long messageId) {
        String raw = String.valueOf(groupId);
        if (raw.startsWith("-100")) {
            return "https://t.me/c/" + raw.substring(4) + "/" + messageId;
        }
        return null;
    }
}
