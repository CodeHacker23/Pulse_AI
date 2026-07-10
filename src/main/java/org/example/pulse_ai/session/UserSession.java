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
    private Integer messageId;
    private int freeDraftsUsed;
    private final java.util.HashSet<Long> draftedIdeaIds = new java.util.HashSet<>();

    public UserSession(long chatId) {
        this.chatId = chatId;
    }

    public void clearFlow() {
        requestId = null;
        postId = null;
        editDraft = null;
        paymentId = null;
        messageId = null;
        freeDraftsUsed = 0;
        draftedIdeaIds.clear();
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
}
