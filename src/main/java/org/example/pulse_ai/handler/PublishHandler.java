package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.example.pulse_ai.domain.analysis.GeneratedPostService;
import org.example.pulse_ai.domain.analysis.PostDraftService;
import org.example.pulse_ai.domain.analysis.PostImageService;
import org.example.pulse_ai.domain.publish.ChannelPublishService;
import org.example.pulse_ai.domain.schedule.PostScheduleService;
import org.example.pulse_ai.domain.schedule.SlotPerformanceService;
import org.example.pulse_ai.domain.user.UserTimezoneService;
import org.example.pulse_ai.persistence.entity.ScheduledPostEntity;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.model.PublishSlotMetric;
import org.example.pulse_ai.telegram.TelegramLimits;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.ConversionCopy;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PublishHandler {

    private static final ZoneId FALLBACK_MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter HUMAN_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final GeneratedPostService generatedPostService;
    private final PostImageService imageService;
    private final PostDraftService postDraftService;
    private final PostScheduleService scheduleService;
    private final SlotPerformanceService slotPerformanceService;
    private final ChannelPublishService publishService;
    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final AnalysisSnapshotService snapshotService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PollHandler pollHandler;
    private final PulseAnalysisProperties analysisProperties;
    private final UserTimezoneService timezoneService;

    public boolean handles(String callbackData) {
        return callbackData != null
                && (callbackData.startsWith(CallbackData.PREFIX_PUBLISH)
                        || callbackData.startsWith(CallbackData.PREFIX_SCHEDULE));
    }

    public void handle(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        messageSender.answerCallback(callbackQueryId);

        if (callbackData.startsWith(CallbackData.PREFIX_SCHEDULE)) {
            handleSchedule(chatId, messageId, user, callbackData);
            return;
        }

        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "previewnew:")) {
            long postId = parseId(callbackData, "previewnew:");
            showPreview(chatId, messageId, user, postId, null);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "preview:")) {
            long postId = parseId(callbackData, "preview:");
            showPreviewFromPhotoCard(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "photo:")) {
            long postId = parseId(callbackData, "photo:");
            attachPhoto(chatId, messageId, user, postId, false);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "shorten:")) {
            long postId = parseId(callbackData, "shorten:");
            shortenThenAttachPhoto(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "rephoto:")) {
            long postId = parseId(callbackData, "rephoto:");
            attachPhoto(chatId, messageId, user, postId, true);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "nophoto:")) {
            long postId = parseId(callbackData, "nophoto:");
            clearPhotoAndPreview(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "when:")) {
            long postId = parseId(callbackData, "when:");
            showPublishWhen(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "confirm:")) {
            long postId = parseId(callbackData, "confirm:");
            confirmPublish(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "edit:")) {
            long postId = parseId(callbackData, "edit:");
            startEdit(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "cancel:")) {
            long postId = parseId(callbackData, "cancel:");
            backToDraft(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PUBLISH + "retry:")) {
            long postId = parseId(callbackData, "retry:");
            confirmPublish(chatId, messageId, user, postId);
        }
    }

    public void showPreview(long chatId, int messageId, UserEntity user, long postId, String overrideText) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }

        if (GeneratedPostService.isPoll(ctx.post())) {
            pollHandler.showPollBuilder(chatId, messageId, ctx.post(), ctx.request().getId());
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        String text = overrideText != null ? overrideText : resolveDraftText(session, ctx.post());
        session.setPostId(postId);
        session.setRequestId(ctx.request().getId());
        session.setEditDraft(text);
        session.setState(BotState.POST_PREVIEW);

        ChannelPublishService.PublishReadiness readiness = publishService.checkReadiness(ctx.channel());
        if (!readiness.allowed()) {
            String blocked = ConversionCopy.publishBlocked(readiness.message());
            InlineKeyboardMarkup kb = keyboards.publishBlockedInline();
            if (messageId > 0) {
                messageSender.editTextOrReplace(chatId, messageId, blocked, kb);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, blocked, kb);
            }
            return;
        }

        String preview = ConversionCopy.publishPreview(ctx.channel().getTitle(), text);
        if (ctx.post().getImageUrl() != null && !ctx.post().getImageUrl().isBlank()) {
            if (!TelegramLimits.fitsPhotoCaption(text)) {
                generatedPostService.setImageUrl(postId, null);
                showCaptionTooLong(chatId, messageId, postId, text);
                return;
            }
            preview = "🖼 <i>Пост выйдет с фото</i>\n\n" + preview;
        } else {
            preview = preview + ConversionCopy.draftPhotoHint(TelegramLimits.fitsPhotoCaption(text));
        }
        InlineKeyboardMarkup keyboard = keyboards.publishPreviewInline(postId);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, preview, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, preview, keyboard);
        }
    }

    private void attachPhoto(long chatId, int messageId, UserEntity user, long postId, boolean another) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        if (!imageService.isConfigured()) {
            messageSender.sendTextSafe(chatId,
                    "🖼 Подбор фото пока не настроен. Добавьте бесплатный ключ Pexels "
                            + "(pulse.images.pexels-api-key) — и бот начнёт подбирать картинки к постам.");
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        String text = resolveDraftText(session, ctx.post());
        if (!TelegramLimits.fitsPhotoCaption(text)) {
            showCaptionTooLong(chatId, messageId, postId, text);
            return;
        }

        var found = another
                ? imageService.anotherForPost(text, ctx.channel().getTitle())
                : imageService.suggestForPost(text, ctx.channel().getTitle());

        if (found.isEmpty()) {
            String note = "🖼 К этому посту фото не подошло. Попробуйте ещё раз или опубликуйте без картинки.";
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, note, keyboards.publishPreviewInline(postId));
            } else {
                messageSender.sendTextWithInlineSafe(chatId, note, keyboards.publishPreviewInline(postId));
            }
            return;
        }

        PostImageService.ImageSuggestion img = found.get();
        generatedPostService.setImageUrl(postId, img.imageUrl());

        String caption = "🖼 <b>Фото для поста</b>\n"
                + "Источник: " + TgHtml.esc(img.author()) + " · Pexels\n\n"
                + "Меню ниже — публикуйте, смените фото или вернитесь к тексту.";
        InlineKeyboardMarkup keyboard = keyboards.photoPreviewInline(postId);
        boolean updated = messageId > 0
                && messageSender.editPhotoUrlSafe(chatId, messageId, img.imageUrl(), caption, keyboard);
        if (!updated) {
            boolean sent = messageSender.sendPhotoUrlWithInlineSafe(
                    chatId, img.imageUrl(), caption, keyboard);
            if (!sent) {
                messageSender.sendTextSafe(chatId,
                        "Не удалось показать фото. Попробуйте «🔄 Другое фото» или опубликуйте без картинки.");
            }
        }
    }

    private void showCaptionTooLong(long chatId, int messageId, long postId, String text) {
        int len = TelegramLimits.captionHtmlLength(text);
        String msg = ConversionCopy.photoCaptionTooLong(len, TelegramLimits.overflowChars(text));
        InlineKeyboardMarkup kb = keyboards.photoCaptionTooLongInline(postId);
        if (messageId > 0) {
            messageSender.editTextOrReplace(chatId, messageId, msg, kb);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, msg, kb);
        }
    }

    private void shortenThenAttachPhoto(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        String text = resolveDraftText(session, ctx.post());
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "✂️ Сокращаю текст под фото…", null);
        } else {
            messageSender.sendTextSafe(chatId, "✂️ Сокращаю текст под фото…");
        }
        String shortened = postDraftService.shortenForPhotoCaption(
                text, analysisProperties.getLlmTimeoutSeconds());
        generatedPostService.replaceText(postId, shortened);
        session.setEditDraft(shortened);
        session.setPostId(postId);
        attachPhoto(chatId, messageId, user, postId, false);
    }

    private void showPublishWhen(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setPostId(postId);
        session.setRequestId(ctx.request().getId());
        session.setState(BotState.POST_PREVIEW);

        boolean hasPhoto = ctx.post().getImageUrl() != null && !ctx.post().getImageUrl().isBlank();
        String text = """
                📤 <b>Когда опубликовать?</b>

                Канал: «%s»
                %s

                Выберите: сразу в эфир или по расписанию.""".formatted(
                TgHtml.esc(ctx.channel().getTitle()),
                hasPhoto ? "🖼 С фото" : "Текст без фото");

        InlineKeyboardMarkup keyboard = keyboards.publishWhenInline(postId);
        if (messageId > 0) {
            messageSender.editTextOrReplace(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private void clearPhotoAndPreview(long chatId, int messageId, UserEntity user, long postId) {
        generatedPostService.setImageUrl(postId, null);
        if (messageId > 0) {
            messageSender.deleteMessageSafe(chatId, messageId);
        }
        showPreview(chatId, 0, user, postId, null);
    }

    private void showPreviewFromPhotoCard(long chatId, int messageId, UserEntity user, long postId) {
        if (messageId > 0) {
            messageSender.deleteMessageSafe(chatId, messageId);
            showPreview(chatId, 0, user, postId, null);
            return;
        }
        showPreview(chatId, messageId, user, postId, null);
    }

    public void startEdit(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        String current = resolveDraftText(session, ctx.post());
        session.setPostId(postId);
        session.setRequestId(ctx.request().getId());
        session.setEditDraft(current);
        session.setScheduledPostId(null);
        session.setState(BotState.POST_EDIT);

        String text = ConversionCopy.publishEditPrompt(current);
        InlineKeyboardMarkup keyboard = keyboards.publishEditInline(postId);
        if (messageId > 0) {
            messageSender.editTextOrReplace(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
        // Сырой markdown отдельным сообщением — удобно копировать и править.
        String raw = current.length() > 3500 ? current.substring(0, 3497) + "…" : current;
        messageSender.sendTextSafe(chatId, "📋 <b>Исходник для копирования</b>\n<pre>"
                + TgHtml.esc(raw) + "</pre>");
    }

    public void handleEditedText(long chatId, UserEntity user, String newText) {
        UserSession session = sessionService.getOrCreate(chatId);
        Long postId = session.getPostId();
        if (postId == null) {
            sessionService.resetToMainMenu(chatId);
            messageSender.sendTextSafe(chatId,
                    "Сессия редактирования сброшена. Откройте пост снова и нажмите «✏️ Править».");
            return;
        }
        String trimmed = newText == null ? "" : newText.trim();
        if (trimmed.isBlank()) {
            messageSender.sendTextSafe(chatId, "Пустой текст. Пришлите текст поста одним сообщением.");
            return;
        }
        generatedPostService.replaceText(postId, trimmed);
        session.setEditDraft(trimmed);
        session.setState(BotState.POST_PREVIEW);
        showPreview(chatId, 0, user, postId, trimmed);
    }

    private void confirmPublish(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        String finalText = resolveDraftText(session, ctx.post());
        session.setState(BotState.POST_CONFIRM);

        String imageUrl = ctx.post().getImageUrl();
        if (imageUrl != null && !imageUrl.isBlank() && !TelegramLimits.fitsPhotoCaption(finalText)) {
            showCaptionTooLong(chatId, messageId, postId, finalText);
            return;
        }

        // С фото-карточки messageId — это фото без текста; EditMessageText падает.
        int uiMessageId = messageId;
        if (uiMessageId > 0) {
            uiMessageId = messageSender.editTextOrReplace(
                    chatId, uiMessageId, ConversionCopy.publishInProgress(ctx.channel().getTitle()), null);
        }

        ChannelPublishService.PublishResult result = publishService.publish(
                user, ctx.channel(), ctx.post(), finalText);

        if (!result.success()) {
            String errorText = ConversionCopy.publishFailed(result.error());
            InlineKeyboardMarkup keyboard = keyboards.publishFailedInline(postId, ctx.request().getId());
            if (uiMessageId > 0) {
                messageSender.editTextOrReplace(chatId, uiMessageId, errorText, keyboard);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, errorText, keyboard);
            }
            return;
        }

        session.clearFlow();
        session.setLastRequestId(ctx.request().getId());
        session.setState(BotState.MAIN_MENU);

        String success = ConversionCopy.publishSuccess(ctx.channel().getTitle(), result.link());
        InlineKeyboardMarkup keyboard = keyboards.publishSuccessInline(ctx.request().getId());
        if (uiMessageId > 0) {
            messageSender.editTextOrReplace(chatId, uiMessageId, success, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, success, keyboard);
        }
    }

    private void backToDraft(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.REQUEST_RESULT);
        session.setEditDraft(null);

        ContentIdeaEntity idea = snapshotService.getIdeas(ctx.request().getId()).stream()
                .filter(i -> i.getId().equals(ctx.post().getIdeaId()))
                .findFirst()
                .orElse(null);
        String ideaTitle = idea != null ? idea.getTitle() : "пост";
        String draftText = generatedPostService.latestText(ctx.post());

        String text = ConversionCopy.draftHeader(ideaTitle)
                + "\n\n"
                + TgHtml.fromMarkdown(draftText)
                + "\n\n<i>Отредактируйте под себя и публикуйте.</i>";

        InlineKeyboardMarkup keyboard = keyboards.draftResultInline(
                ctx.request().getId(),
                ctx.post().getIdeaId(),
                postId,
                0,
                false,
                false,
                99);
        messageSender.editText(chatId, messageId, text, keyboard);
    }

    // ===== Планировщик отложенной публикации =====

    private void handleSchedule(long chatId, int messageId, UserEntity user, String callbackData) {
        String tail = callbackData.substring(CallbackData.PREFIX_SCHEDULE.length());
        if (tail.equals("list")) {
            showScheduledList(chatId, messageId, user);
            return;
        }
        if (tail.startsWith("at:")) {
            String[] parts = tail.split(":");
            if (parts.length >= 3) {
                long epoch = Long.parseLong(parts[1]);
                long postId = Long.parseLong(parts[2]);
                scheduleAt(chatId, messageId, user, postId, Instant.ofEpochSecond(epoch));
            }
            return;
        }
        int colon = tail.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String action = tail.substring(0, colon);
        long id = Long.parseLong(tail.substring(colon + 1));
        switch (action) {
            case "open" -> showScheduleOptions(chatId, messageId, user, id);
            case "best" -> scheduleBest(chatId, messageId, user, id);
            case "1h" -> scheduleAt(chatId, messageId, user, id, Instant.now().plusSeconds(3600));
            case "eve" -> scheduleAt(chatId, messageId, user, id, todayOrNextAt(user, 18, 0, false));
            case "morn" -> scheduleAt(chatId, messageId, user, id, todayOrNextAt(user, 10, 0, true));
            case "custom" -> startCustomSchedule(chatId, messageId, user, id);
            case "retime" -> startReschedule(chatId, messageId, user, id);
            case "cancel" -> cancelScheduled(chatId, messageId, user, id);
            default -> {
            }
        }
    }

    private void showScheduleOptions(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setPostId(postId);
        session.setRequestId(ctx.request().getId());

        PublishSlotMetric best = bestSlot(ctx.request().getId(), ctx.channel().getId());
        String bestBucket = null;
        StringBuilder text = new StringBuilder("📅 <b>Когда опубликовать?</b>\n\n");
        if (best != null) {
            bestBucket = best.time();
            text.append("🎯 По вашей статистике сильнее всего заходят посты в <b>")
                    .append(TgHtml.esc(best.day().toLowerCase()))
                    .append("</b> около <b>").append(TgHtml.esc(best.time())).append("</b>");
            if (best.avgViews() > 0) {
                text.append(" (в среднем ~").append(best.avgViews()).append(" просмотров)");
            }
            text.append(".\n\n");
        }
        text.append("Выберите время. <b>🔥</b> — лучший охват по вашей статистике.");
        editOrSend(chatId, messageId, text.toString(),
                keyboards.scheduleOptionsInline(postId, buildTimeSlots(user, bestBucket)));
    }

    /** Кандидаты: только будущее (≥15 мин) в TZ юзера; 🔥 — бакет из статистики. */
    private List<KeyboardFactory.TimeSlot> buildTimeSlots(UserEntity user, String bestBucket) {
        int[] hours = {9, 12, 15, 18, 21};
        ZoneId zone = zoneOf(user);
        Instant soonest = Instant.now().plusSeconds(15 * 60);
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<KeyboardFactory.TimeSlot> slots = new ArrayList<>();
        for (int dayOffset = 0; dayOffset <= 1 && slots.size() < 6; dayOffset++) {
            String dayLabel = dayOffset == 0 ? "Сегодня" : "Завтра";
            for (int h : hours) {
                if (slots.size() >= 6) {
                    break;
                }
                ZonedDateTime when = now.plusDays(dayOffset)
                        .withHour(h).withMinute(0).withSecond(0).withNano(0);
                if (!when.toInstant().isBefore(soonest)) {
                    slots.add(slot(dayLabel, when, bestBucket));
                }
            }
        }
        return slots;
    }

    private KeyboardFactory.TimeSlot slot(String dayLabel, ZonedDateTime when, String bestBucket) {
        String label = dayLabel + " " + String.format("%02d:00", when.getHour());
        boolean hot = bestBucket != null && bucketOf(when.getHour()).equals(bestBucket);
        return new KeyboardFactory.TimeSlot(label, when.toEpochSecond(), hot);
    }

    private static String bucketOf(int hour) {
        return hour < 12 ? "09:00" : hour < 17 ? "14:00" : "19:00";
    }

    /**
     * Лучший слот с учётом обучения: базовый охват из анализа домножается на накопленный
     * множитель эффективности слота (факт/среднее), затем берётся сильнейший.
     */
    private PublishSlotMetric bestSlot(Long requestId, Long channelId) {
        List<PublishSlotMetric> slots = snapshotService.getBestSlots(requestId);
        if (slots.isEmpty()) {
            return null;
        }
        return slots.stream()
                .max(java.util.Comparator.comparingDouble(s ->
                        Math.max(1, s.avgViews())
                                * slotPerformanceService.multiplier(
                                        channelId, SlotPerformanceService.slotKey(s.day(), s.time()))))
                .orElse(slots.get(0));
    }

    private void scheduleBest(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        PublishSlotMetric best = bestSlot(ctx.request().getId(), ctx.channel().getId());
        Instant when = best != null
                ? nextSlotOccurrence(user, best.day(), best.time())
                : todayOrNextAt(user, 19, 0, false);
        scheduleAt(chatId, messageId, user, postId, when);
    }

    private void startCustomSchedule(long chatId, int messageId, UserEntity user, long postId) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setPostId(postId);
        session.setRequestId(ctx.request().getId());
        session.setScheduledPostId(null);
        session.setState(BotState.SCHEDULE_TIME_INPUT);
        ZoneId zone = zoneOf(user);
        String example = HUMAN_TIME.format(ZonedDateTime.now(zone).plusDays(1).withHour(18).withMinute(0));
        String text = "✏️ <b>Своё время</b>\n\n"
                + "Пришлите дату и время в формате <b>ДД.ММ ЧЧ:ММ</b>.\n"
                + "Например: <code>" + example + "</code>";
        editOrSend(chatId, messageId, text, keyboards.backToMainInline());
    }

    private void startReschedule(long chatId, int messageId, UserEntity user, long scheduledId) {
        ScheduledPostEntity item = scheduleService.find(scheduledId)
                .filter(e -> e.getUserId().equals(user.getId())
                        && e.getStatus() == org.example.pulse_ai.domain.schedule.ScheduledPostStatus.PENDING)
                .orElse(null);
        if (item == null) {
            messageSender.sendTextSafe(chatId, "Запланированный пост не найден или уже опубликован.");
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setScheduledPostId(scheduledId);
        session.setPostId(null);
        session.setState(BotState.SCHEDULE_TIME_INPUT);
        ZoneId zone = zoneOf(user);
        String current = HUMAN_TIME.format(ZonedDateTime.ofInstant(item.getScheduledAt(), zone));
        String example = HUMAN_TIME.format(ZonedDateTime.now(zone).plusDays(1).withHour(18).withMinute(0));
        String text = "🕐 <b>Новое время для #" + scheduledId + "</b>\n\n"
                + "Сейчас: <b>" + current + "</b>\n"
                + "Пришлите <b>ДД.ММ ЧЧ:ММ</b>, например <code>" + example + "</code>\n"
                + "<i>/cancel — отмена</i>";
        editOrSend(chatId, messageId, text, keyboards.backToMainInline());
    }

    public void handleScheduleTimeInput(long chatId, UserEntity user, String rawText) {
        UserSession session = sessionService.getOrCreate(chatId);
        Instant when = parseCustomTime(rawText, zoneOf(user));
        if (when == null) {
            messageSender.sendTextSafe(chatId,
                    "Не понял время. Формат: <b>ДД.ММ ЧЧ:ММ</b>, например 25.07 18:30");
            return;
        }
        if (when.isBefore(Instant.now().plusSeconds(30))) {
            messageSender.sendTextSafe(chatId, "Это время уже прошло. Выберите время в будущем.");
            return;
        }

        Long scheduledId = session.getScheduledPostId();
        if (scheduledId != null) {
            boolean ok = scheduleService.reschedule(scheduledId, user.getId(), when);
            session.setScheduledPostId(null);
            session.setState(BotState.MAIN_MENU);
            if (!ok) {
                messageSender.sendTextSafe(chatId, "Не удалось перенести #" + scheduledId + ".");
                return;
            }
            ZoneId zone = zoneOf(user);
            String whenStr = HUMAN_TIME.format(ZonedDateTime.ofInstant(when, zone));
            List<ScheduledPostEntity> items = scheduleService.pending(user.getId());
            messageSender.sendTextWithInlineSafe(chatId,
                    "✅ #" + scheduledId + " перенесён на <b>" + whenStr + "</b>",
                    items.isEmpty() ? keyboards.backToMainInline() : keyboards.scheduledListInline(items));
            return;
        }

        Long postId = session.getPostId();
        if (postId == null) {
            sessionService.resetToMainMenu(chatId);
            messageSender.sendTextSafe(chatId, "Черновик не найден. Откройте пост заново.");
            return;
        }
        scheduleAt(chatId, 0, user, postId, when);
    }

    private void scheduleAt(long chatId, int messageId, UserEntity user, long postId, Instant when) {
        PostContext ctx = loadPostContext(postId, user).orElse(null);
        if (ctx == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        if (when.isBefore(Instant.now().plusSeconds(30))) {
            messageSender.sendTextSafe(chatId, "Это время уже прошло. Выберите время в будущем.");
            return;
        }

        ChannelPublishService.PublishReadiness readiness = publishService.checkReadiness(ctx.channel());
        if (!readiness.allowed()) {
            editOrSend(chatId, messageId, ConversionCopy.publishBlocked(readiness.message()),
                    keyboards.publishBlockedInline());
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        String finalText = resolveDraftText(session, ctx.post());
        String imageUrl = ctx.post().getImageUrl();
        ScheduledPostEntity saved;
        if (GeneratedPostService.isPoll(ctx.post())) {
            saved = scheduleService.schedule(
                    user.getId(), ctx.channel().getId(), postId, finalText, null, when,
                    "POLL", ctx.post().getPollOptions(), ctx.post().isPollAnonymous());
        } else {
            saved = scheduleService.schedule(
                    user.getId(), ctx.channel().getId(), postId, finalText, imageUrl, when);
        }

        session.clearFlow();
        session.setLastRequestId(ctx.request().getId());
        session.setState(BotState.MAIN_MENU);

        ZoneId zone = zoneOf(user);
        String whenStr = HUMAN_TIME.format(ZonedDateTime.ofInstant(when, zone));
        boolean poll = GeneratedPostService.isPoll(ctx.post());
        String text = "📅 <b>" + (poll ? "Опрос запланирован" : "Пост запланирован") + "</b>\n\n"
                + "Канал: «" + TgHtml.esc(ctx.channel().getTitle()) + "»\n"
                + "Время: <b>" + whenStr + "</b>"
                + (poll
                    ? "\n📊 Опрос · " + (ctx.post().isPollAnonymous() ? "анонимный" : "видно, кто голосовал")
                    : (imageUrl != null && !imageUrl.isBlank() ? "\n🖼 С фото" : ""))
                + "\n\nОпубликую автоматически. Можете сразу сделать следующий пост.";
        editOrSend(chatId, messageId, text,
                keyboards.scheduledConfirmInline(saved.getId(), ctx.request().getId()));
    }

    public void openScheduledList(long chatId, UserEntity user) {
        showScheduledList(chatId, 0, user);
    }

    private void showScheduledList(long chatId, int messageId, UserEntity user) {
        List<ScheduledPostEntity> items = scheduleService.pending(user.getId());
        if (items.isEmpty()) {
            editOrSend(chatId, messageId,
                    "📅 <b>Запланированные посты</b>\n\nПока пусто. Сгенерируйте пост и нажмите «📅 Запланировать».",
                    keyboards.backToMainInline());
            return;
        }
        editOrSend(chatId, messageId, scheduledListBody(user, items, null), keyboards.scheduledListInline(items));
    }

    private void cancelScheduled(long chatId, int messageId, UserEntity user, long id) {
        boolean ok = scheduleService.cancel(id, user.getId());
        String note = ok
                ? "❌ Публикация #" + id + " отменена."
                : "Не удалось отменить #" + id + " (возможно, уже опубликовано).";
        List<ScheduledPostEntity> items = scheduleService.pending(user.getId());
        if (items.isEmpty()) {
            editOrSend(chatId, messageId, note + "\n\nБольше нет запланированных постов.",
                    keyboards.backToMainInline());
        } else {
            editOrSend(chatId, messageId, scheduledListBody(user, items, note), keyboards.scheduledListInline(items));
        }
    }

    private String scheduledListBody(UserEntity user, List<ScheduledPostEntity> items, String note) {
        ZoneId zone = zoneOf(user);
        StringBuilder sb = new StringBuilder();
        if (note != null) {
            sb.append(note).append("\n\n");
        }
        sb.append("📅 <b>Запланированные посты</b>\n");
        sb.append("<i>🕐 — сменить время · ❌ — отменить</i>\n\n");
        for (ScheduledPostEntity item : items) {
            String whenStr = HUMAN_TIME.format(ZonedDateTime.ofInstant(item.getScheduledAt(), zone));
            String preview = item.getFinalText() != null ? item.getFinalText() : "";
            preview = preview.replace('\n', ' ').trim();
            if (preview.length() > 60) {
                preview = preview.substring(0, 57) + "…";
            }
            sb.append("#").append(item.getId()).append(" · <b>").append(whenStr).append("</b>\n")
                    .append(TgHtml.esc(preview)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private Instant nextSlotOccurrence(UserEntity user, String russianDay, String time) {
        int hour = 19;
        int minute = 0;
        try {
            String[] t = time.split(":");
            hour = Integer.parseInt(t[0].trim());
            minute = Integer.parseInt(t[1].trim());
        } catch (Exception ignored) {
            // дефолт 19:00
        }
        ZoneId zone = zoneOf(user);
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        java.time.DayOfWeek target = parseRussianDay(russianDay);
        if (target != null) {
            int diff = (target.getValue() - candidate.getDayOfWeek().getValue() + 7) % 7;
            candidate = candidate.plusDays(diff);
            if (!candidate.isAfter(now.plusMinutes(5))) {
                candidate = candidate.plusDays(7);
            }
        } else if (!candidate.isAfter(now.plusMinutes(5))) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private static java.time.DayOfWeek parseRussianDay(String day) {
        if (day == null) {
            return null;
        }
        return switch (day.trim().toLowerCase()) {
            case "понедельник" -> java.time.DayOfWeek.MONDAY;
            case "вторник" -> java.time.DayOfWeek.TUESDAY;
            case "среда" -> java.time.DayOfWeek.WEDNESDAY;
            case "четверг" -> java.time.DayOfWeek.THURSDAY;
            case "пятница" -> java.time.DayOfWeek.FRIDAY;
            case "суббота" -> java.time.DayOfWeek.SATURDAY;
            case "воскресенье" -> java.time.DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private Instant todayOrNextAt(UserEntity user, int hour, int minute, boolean forceTomorrow) {
        ZoneId zone = zoneOf(user);
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (forceTomorrow || !candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private Instant parseCustomTime(String raw, ZoneId zone) {
        try {
            String s = raw.trim().replaceAll("\\s+", " ");
            String[] parts = s.split(" ");
            if (parts.length != 2) {
                return null;
            }
            String[] d = parts[0].split("\\.");
            String[] t = parts[1].split(":");
            if (d.length < 2 || t.length != 2) {
                return null;
            }
            int day = Integer.parseInt(d[0]);
            int month = Integer.parseInt(d[1]);
            int hour = Integer.parseInt(t[0]);
            int minute = Integer.parseInt(t[1]);
            boolean hasYear = d.length >= 3;
            int year = hasYear ? normalizeYear(Integer.parseInt(d[2])) : ZonedDateTime.now(zone).getYear();
            ZonedDateTime when = LocalDateTime.of(year, month, day, hour, minute).atZone(zone);
            if (!hasYear && when.isBefore(ZonedDateTime.now(zone))) {
                when = when.plusYears(1);
            }
            return when.toInstant();
        } catch (Exception ex) {
            return null;
        }
    }

    private ZoneId zoneOf(UserEntity user) {
        if (user == null) {
            return FALLBACK_MSK;
        }
        return timezoneService.zoneOf(user.getId());
    }

    private static int normalizeYear(int y) {
        return y < 100 ? 2000 + y : y;
    }

    private void editOrSend(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            // С фото-карточки EditMessageText падает — заменяем сообщение
            messageSender.editTextOrReplace(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private Optional<PostContext> loadPostContext(long postId, UserEntity user) {
        GeneratedPostEntity post = generatedPostService.findById(postId).orElse(null);
        if (post == null) {
            return Optional.empty();
        }
        AnalysisRequestEntity request = requestRepository.findById(post.getRequestId()).orElse(null);
        if (request == null || !request.getUserId().equals(user.getId())) {
            return Optional.empty();
        }
        ChannelEntity channel = channelRepository.findById(request.getChannelId()).orElse(null);
        if (channel == null) {
            return Optional.empty();
        }
        return Optional.of(new PostContext(post, request, channel));
    }

    private static String resolveDraftText(UserSession session, GeneratedPostEntity post) {
        if (session.getEditDraft() != null && !session.getEditDraft().isBlank()) {
            return session.getEditDraft().trim();
        }
        if (post.getVariantB() != null && !post.getVariantB().isBlank()) {
            return post.getVariantB().trim();
        }
        return post.getVariantA().trim();
    }

    private static long parseId(String callbackData, String suffix) {
        String idStr = callbackData.substring((CallbackData.PREFIX_PUBLISH + suffix).length());
        return Long.parseLong(idStr);
    }

    private record PostContext(GeneratedPostEntity post, AnalysisRequestEntity request, ChannelEntity channel) {
    }
}
