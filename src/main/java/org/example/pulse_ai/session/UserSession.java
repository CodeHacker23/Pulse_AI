package org.example.pulse_ai.session;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserSession {

    private final long chatId;
    private BotState state = BotState.MAIN_MENU;
    private Long channelId;
    private Long requestId;
    private Long postId;
    private String editDraft;
    private Long paymentId;
    private Long productPostId;
    private String productEditDraft;
    private Integer messageId;
    private Long pendingLeadId;
    /** Последний завершённый разбор — чтобы из меню сразу идти к идеям/постам без повторного анализа. */
    private Long lastRequestId;
    /** Перенос уже запланированной публикации (sched:retime). */
    private Long scheduledPostId;
    /** Ввод цены админа по сделке рекламы. */
    private Long adDealId;
    private int freeDraftsUsed;
    private final java.util.HashSet<Long> draftedIdeaIds = new java.util.HashSet<>();
    private final java.util.HashMap<Long, Integer> ideasRegensByRequest = new java.util.HashMap<>();
    private String outreachScenario;
    private String outreachSourceDraft;
    private Long outreachCampaignId;

    public UserSession(long chatId) {
        this.chatId = chatId;
    }

    public void clearFlow() {
        requestId = null;
        postId = null;
        editDraft = null;
        paymentId = null;
        productPostId = null;
        productEditDraft = null;
        messageId = null;
        freeDraftsUsed = 0;
        draftedIdeaIds.clear();
        outreachScenario = null;
        outreachSourceDraft = null;
        outreachCampaignId = null;
        scheduledPostId = null;
        adDealId = null;
    }

    public void clearProductFlow() {
        productPostId = null;
        productEditDraft = null;
    }

    /** Возвращает false, если бесплатный лимит исчерпан (повтор того же ideaId не считается). */
    public boolean tryConsumeFreeDraft(long ideaId, int maxFree) {
        if (draftedIdeaIds.contains(ideaId)) {
            return true;
        }
        if (freeDraftsUsed >= maxFree) {
            return false;
        }
        draftedIdeaIds.add(ideaId);
        freeDraftsUsed++;
        return true;
    }

    public int freeDraftsRemaining() {
        return Math.max(0, 3 - freeDraftsUsed);
    }

    public int draftsRemaining(int maxDrafts) {
        return Math.max(0, maxDrafts - freeDraftsUsed);
    }

    public int ideasRegensUsed(Long requestId) {
        if (requestId == null) {
            return 0;
        }
        return ideasRegensByRequest.getOrDefault(requestId, 0);
    }

    public int ideasRegensRemaining(Long requestId, int maxRegens) {
        return Math.max(0, maxRegens - ideasRegensUsed(requestId));
    }

    public void consumeIdeasRegen(Long requestId) {
        if (requestId == null) {
            return;
        }
        ideasRegensByRequest.merge(requestId, 1, Integer::sum);
    }
}
