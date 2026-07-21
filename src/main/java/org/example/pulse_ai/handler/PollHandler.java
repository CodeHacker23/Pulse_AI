package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.analysis.GeneratedPostService;
import org.example.pulse_ai.domain.analysis.PollDraftService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

/**
 * Сборка нативного опроса Telegram: предложенные или свои варианты, анонимность, публикация / план.
 */
@Component
@RequiredArgsConstructor
public class PollHandler {

    private final GeneratedPostService generatedPostService;
    private final PollDraftService pollDraftService;
    private final AnalysisSnapshotService snapshotService;
    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void handle(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        messageSender.answerCallback(callbackQueryId);
        String tail = callbackData.substring(CallbackData.PREFIX_POLL.length());
        if (tail.startsWith("anon:")) {
            toggleAnon(chatId, messageId, user, Long.parseLong(tail.substring(5)));
        } else if (tail.startsWith("custom:")) {
            promptCustom(chatId, user, Long.parseLong(tail.substring(7)));
        } else if (tail.startsWith("regen:")) {
            regenerate(chatId, messageId, user, Long.parseLong(tail.substring(6)));
        }
    }

    public void showPollBuilder(long chatId, int messageId, GeneratedPostEntity post, long requestId) {
        String text = buildPreviewText(post);
        InlineKeyboardMarkup kb = keyboards.pollBuilderInline(post.getId(), requestId, post.isPollAnonymous());
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, kb);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, kb);
        }
    }

    public void handleCustomOptionsInput(long chatId, UserEntity user, String text) {
        UserSession session = sessionService.getOrCreate(chatId);
        Long postId = session.getPostId();
        session.setState(BotState.MAIN_MENU);
        if (postId == null) {
            messageSender.sendText(chatId, "Черновик опроса не найден. Откройте идею снова.");
            return;
        }
        GeneratedPostEntity post = generatedPostService.findById(postId).orElse(null);
        if (post == null || !GeneratedPostService.isPoll(post) || !ownsRequest(user, post.getRequestId())) {
            messageSender.sendText(chatId, "Опрос не найден.");
            return;
        }
        List<String> options = PollDraftService.parseOptionsLines(text);
        if (options.size() < 2) {
            messageSender.sendText(chatId,
                    "Нужно минимум 2 варианта. Пришлите по одному на строку, например:\n\n"
                            + "До 1 000 ₽\n1–3 000 ₽\nОт 3 000 ₽\nТолько бесплатно");
            session.setState(BotState.POLL_OPTIONS_INPUT);
            return;
        }
        if (options.size() > 10) {
            options = options.subList(0, 10);
        }
        generatedPostService.updatePollOptions(postId, options);
        post = generatedPostService.findById(postId).orElse(post);
        showPollBuilder(chatId, 0, post, post.getRequestId());
    }

    private void toggleAnon(long chatId, int messageId, UserEntity user, long postId) {
        GeneratedPostEntity post = loadOwnedPoll(user, postId);
        if (post == null) {
            return;
        }
        generatedPostService.setPollAnonymous(postId, !post.isPollAnonymous());
        post = generatedPostService.findById(postId).orElse(post);
        showPollBuilder(chatId, messageId, post, post.getRequestId());
    }

    private void promptCustom(long chatId, UserEntity user, long postId) {
        GeneratedPostEntity post = loadOwnedPoll(user, postId);
        if (post == null) {
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setPostId(postId);
        session.setState(BotState.POLL_OPTIONS_INPUT);
        messageSender.sendText(chatId,
                "✏️ Пришлите варианты ответа <b>по одному на строку</b> (2–10 штук).\n\n"
                        + "Пример:\n"
                        + "До 1 000 ₽\n"
                        + "1–3 000 ₽\n"
                        + "От 3 000 ₽\n"
                        + "Только если бесплатно");
    }

    private void regenerate(long chatId, int messageId, UserEntity user, long postId) {
        GeneratedPostEntity post = loadOwnedPoll(user, postId);
        if (post == null) {
            return;
        }
        ContentIdeaEntity idea = snapshotService.getIdeas(post.getRequestId()).stream()
                .filter(i -> i.getId().equals(post.getIdeaId()))
                .findFirst()
                .orElse(null);
        if (idea == null) {
            messageSender.sendTextSafe(chatId, "Идея не найдена.");
            return;
        }
        String channelTitle = requestRepository.findById(post.getRequestId())
                .flatMap(r -> channelRepository.findById(r.getChannelId()))
                .map(c -> c.getTitle())
                .orElse("");
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ Подбираю другие варианты…", null);
        }
        PollDraftService.PollDraft draft = pollDraftService.generate(channelTitle, idea);
        GeneratedPostEntity saved = generatedPostService.savePollDraft(
                post.getRequestId(), idea, draft.question(), draft.options(), post.isPollAnonymous());
        showPollBuilder(chatId, messageId, saved, post.getRequestId());
    }

    private GeneratedPostEntity loadOwnedPoll(UserEntity user, long postId) {
        GeneratedPostEntity post = generatedPostService.findById(postId).orElse(null);
        if (post == null || !GeneratedPostService.isPoll(post) || !ownsRequest(user, post.getRequestId())) {
            return null;
        }
        return post;
    }

    private boolean ownsRequest(UserEntity user, Long requestId) {
        if (requestId == null) {
            return false;
        }
        return requestRepository.findById(requestId)
                .map(r -> r.getUserId().equals(user.getId()))
                .orElse(false);
    }

    public static String buildPreviewText(GeneratedPostEntity post) {
        List<String> options = GeneratedPostService.splitOptions(post.getPollOptions());
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Опрос для канала</b>\n\n");
        sb.append("<b>Вопрос:</b>\n").append(TgHtml.esc(post.getVariantA())).append("\n\n");
        sb.append("<b>Варианты:</b>\n");
        int i = 1;
        for (String opt : options) {
            sb.append(i++).append(". ").append(TgHtml.esc(opt)).append('\n');
        }
        sb.append('\n');
        if (post.isPollAnonymous()) {
            sb.append("🕶 <b>Анонимный</b> — опрос уйдёт <b>прямо в канал</b>. "
                    + "Проценты видны, кто голосовал — скрыто.\n");
        } else {
            sb.append("👁 <b>Неанонимный</b> — видно, кто голосовал.\n");
            sb.append("Telegram не пускает такой опрос в ленту канала, поэтому бот:\n");
            sb.append("• выложит опрос в <b>комментарии</b> (группа обсуждений)\n");
            sb.append("• и анонс со ссылкой — в канал\n");
            sb.append("<i>Нужны включённые обсуждения + бот админ в группе комментариев.</i>\n");
        }
        sb.append("\nМожно взять предложенные варианты, ввести свои — потом опубликовать сейчас или запланировать.");
        return sb.toString();
    }
}
