package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
import org.example.pulse_ai.domain.analysis.IdeasGenerationService;
import org.example.pulse_ai.domain.analysis.PostDraftService;
import org.example.pulse_ai.domain.request.RequestType;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.external.ExternalChannelMetrics;
import org.example.pulse_ai.stats.external.ExternalMetricsService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.ConversionCopy;
import org.example.pulse_ai.text.StatsMessageBuilder;
import org.example.pulse_ai.text.TgHtml;
import org.example.pulse_ai.visual.AnalysisChartPack;
import org.example.pulse_ai.visual.AnalysisChartRenderer;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResultCallbackHandler {

    private final AnalysisSnapshotService snapshotService;
    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final AnalyticsService analyticsService;
    private final ExternalMetricsService externalMetricsService;
    private final StatsMessageBuilder statsMessageBuilder;
    private final AnalysisChartRenderer chartRenderer;
    private final IdeasGenerationService ideasGenerationService;
    private final PostDraftService postDraftService;
    private final PulseAnalysisProperties analysisProperties;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;
    private final UserSessionService sessionService;

    public boolean handles(String callbackData) {
        return callbackData.startsWith(CallbackData.PREFIX_RESULT);
    }

    public void handle(long chatId, int messageId, String callbackQueryId, String callbackData) {
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "lock:")
                || callbackData.startsWith(CallbackData.PREFIX_RESULT + "draftlock:")) {
            messageSender.answerCallbackWithAlert(callbackQueryId, ConversionCopy.lockAlert());
            return;
        }
        messageSender.answerCallback(callbackQueryId);

        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "sec:")) {
            handleSection(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "stats:")) {
            handleStats(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "hub:")) {
            handleBackToSection(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "ideas:")) {
            handleIdeas(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "charts:")) {
            handleCharts(chatId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "draft:")) {
            handleDraft(chatId, messageId, callbackData);
            return;
        }
        log.debug("Unhandled result callback: {}", callbackData);
    }

    private void handleBackToSection(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "hub:");
        List<DeepAnalysisSections.Section> sections = snapshotService.getSections(requestId);
        if (sections.isEmpty()) {
            return;
        }
        boolean teaser = isTeaserRequest(requestId);
        editSectionMessage(chatId, messageId, requestId, sections, 0, teaser);
    }

    private void handleStats(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "stats:");
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        AnalysisMetrics metrics = analyticsService.analyze(
                ctx.channel().getId(),
                ctx.request().getPeriodFrom(),
                ctx.request().getPeriodTo()
        );
        ExternalChannelMetrics external = externalMetricsService.bestMetrics(ctx.channel().getUsername());
        int subscribers = ctx.channel().getSubscriberCount() != null ? ctx.channel().getSubscriberCount() : 0;
        if (external.subscribers() != null && external.subscribers() > 0) {
            subscribers = external.subscribers();
        }

        String text = statsMessageBuilder.build(
                requestId, ctx.channel().getTitle(), subscribers, metrics, external);
        boolean teaser = isTeaserRequest(requestId);
        InlineKeyboardMarkup keyboard = keyboards.analysisSectionsInline(requestId, 0,
                Math.max(snapshotService.getSections(requestId).size(), 1), teaser);
        messageSender.editText(chatId, messageId, text, keyboard);
    }

    private void handleSection(long chatId, int messageId, String callbackData) {
        String tail = callbackData.substring((CallbackData.PREFIX_RESULT + "sec:").length());
        int colon = tail.lastIndexOf(':');
        if (colon <= 0) {
            return;
        }
        long requestId = Long.parseLong(tail.substring(0, colon));
        int index = Integer.parseInt(tail.substring(colon + 1));

        List<DeepAnalysisSections.Section> sections = snapshotService.getSections(requestId);
        if (sections.isEmpty() || index < 0 || index >= sections.size()) {
            return;
        }

        boolean teaser = isTeaserRequest(requestId);
        if (DeepAnalysisSections.isIdeasFunnelIndex(index, sections.size())) {
            handleIdeas(chatId, messageId, requestId, sections.size());
            return;
        }
        if (teaser && index > 0 && index < sections.size() - 1) {
            return;
        }

        editSectionMessage(chatId, messageId, requestId, sections, index, teaser);
    }

    private void handleIdeas(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "ideas:");
        int sectionTotal = Math.max(snapshotService.getSections(requestId).size(), DeepAnalysisSections.sectionCount());
        handleIdeas(chatId, messageId, requestId, sectionTotal);
    }

    private void handleIdeas(long chatId, int messageId, long requestId, int sectionTotal) {
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        List<ContentIdeaEntity> ideas = ensureIdeas(ctx);
        if (ideas.isEmpty()) {
            messageSender.sendTextSafe(chatId, "❌ Не удалось сгенерировать идеи. Попробуйте позже.");
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        int draftsLeft = ctx.freeTier() ? session.freeDraftsRemaining() : 99;
        boolean teaser = isTeaserRequest(requestId);
        String text = buildIdeasMessage(ctx.channel().getTitle(), ideas, ctx.freeTier(), draftsLeft);
        List<Long> ideaIds = ideas.stream().limit(3).map(ContentIdeaEntity::getId).toList();
        InlineKeyboardMarkup keyboard = keyboards.ideasFunnelInline(
                requestId, sectionTotal, teaser, ideaIds, ctx.freeTier(), draftsLeft);

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private void handleCharts(long chatId, String callbackData) {
        long requestId = parseRequestId(callbackData, "charts:");
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        AnalysisMetrics metrics = analyticsService.analyze(
                ctx.channel().getId(),
                ctx.request().getPeriodFrom(),
                ctx.request().getPeriodTo()
        );
        AnalysisChartPack charts = chartRenderer.render(ctx.channel().getTitle(), metrics);

        List<TelegramMessageSender.AlbumPhoto> album = new ArrayList<>();
        album.add(new TelegramMessageSender.AlbumPhoto(charts.engagementHeatmap(), "🔥 <b>Активность по дням</b>"));
        album.add(new TelegramMessageSender.AlbumPhoto(charts.topPosts(), "🏆 <b>Топ постов</b>"));
        if (!messageSender.sendPhotoAlbumSafe(chatId, album)) {
            messageSender.sendPhotoSafe(chatId, charts.engagementHeatmap(), "🔥 Активность по дням");
            messageSender.sendPhotoSafe(chatId, charts.topPosts(), "🏆 Топ постов");
        }
    }

    private void handleDraft(long chatId, int messageId, String callbackData) {
        // result:draft:{requestId}:{ideaId}
        String tail = callbackData.substring((CallbackData.PREFIX_RESULT + "draft:").length());
        int colon = tail.indexOf(':');
        if (colon <= 0) {
            return;
        }
        long requestId = Long.parseLong(tail.substring(0, colon));
        long ideaId = Long.parseLong(tail.substring(colon + 1));

        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        if (ctx.freeTier() && !session.tryConsumeFreeDraft(ideaId, 3)) {
            String paywall = ConversionCopy.draftPaywall();
            int sectionTotal = Math.max(snapshotService.getSections(requestId).size(), DeepAnalysisSections.sectionCount());
            boolean teaser = isTeaserRequest(requestId);
            InlineKeyboardMarkup keyboard = keyboards.draftResultInline(
                    requestId, ideaId, sectionTotal, teaser, true, 0);
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, paywall, keyboard);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, paywall, keyboard);
            }
            return;
        }

        ContentIdeaEntity idea = snapshotService.getIdeas(requestId).stream()
                .filter(i -> i.getId().equals(ideaId))
                .findFirst()
                .orElse(null);
        if (idea == null) {
            messageSender.sendTextSafe(chatId, "❌ Идея не найдена.");
            return;
        }

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, ConversionCopy.draftGenerating(), null);
        }

        AnalysisMetrics metrics = analyticsService.analyze(
                ctx.channel().getId(),
                ctx.request().getPeriodFrom(),
                ctx.request().getPeriodTo()
        );
        String draft = postDraftService.generateDraft(
                ctx.channel().getTitle(),
                idea,
                metrics,
                analysisProperties.getLlmTimeoutSeconds()
        );

        int draftsLeft = ctx.freeTier() ? session.freeDraftsRemaining() : 99;
        int sectionTotal = Math.max(snapshotService.getSections(requestId).size(), DeepAnalysisSections.sectionCount());
        boolean teaser = isTeaserRequest(requestId);

        String text = ConversionCopy.draftHeader(idea.getTitle())
                + "\n\n"
                + TgHtml.fromMarkdown(draft)
                + footerBlock(ctx.freeTier(), draftsLeft);
        InlineKeyboardMarkup keyboard = keyboards.draftResultInline(
                requestId, ideaId, sectionTotal, teaser, ctx.freeTier(), draftsLeft);

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private static String footerBlock(boolean freeTier, int draftsLeft) {
        if (freeTier && draftsLeft > 0) {
            return "\n\n🎁 Осталось <b>" + draftsLeft + "</b> бесплатных черновиков — попробуйте ещё.";
        }
        if (freeTier) {
            return """

                    ━━━━━━━━━━━━━━━
                    🔥 <b>Вы попробовали генерацию.</b>

                    В полном запросе:
                    • 12 идей вместо 3
                    • 7 готовых постов с вариантами текста
                    • все 5 разделов разбора

                    <i>Один клик — и следующий пост уже не «с чистого листа».</i>""";
        }
        return "\n\n<i>Отредактируйте под себя и публикуйте.</i>";
    }

    private List<ContentIdeaEntity> ensureIdeas(RequestContext ctx) {
        List<ContentIdeaEntity> ideas = snapshotService.getIdeas(ctx.request().getId());
        if (!ideas.isEmpty()) {
            return ideas;
        }
        try {
            AnalysisMetrics metrics = analyticsService.analyze(
                    ctx.channel().getId(),
                    ctx.request().getPeriodFrom(),
                    ctx.request().getPeriodTo()
            );
            int count = 3;
            ideas = ideasGenerationService.generateIdeas(
                    ctx.request().getId(),
                    ctx.channel().getTitle(),
                    metrics,
                    count,
                    analysisProperties.getLlmTimeoutSeconds()
            );
            snapshotService.saveIdeas(ideas);
        } catch (Exception ex) {
            log.warn("Lazy ideas generation failed for request {}: {}", ctx.request().getId(), ex.getMessage());
        }
        return ideas;
    }

    private String buildIdeasMessage(String channelTitle, List<ContentIdeaEntity> ideas, boolean freeTier, int draftsLeft) {
        StringBuilder sb = new StringBuilder(ConversionCopy.ideasIntro(channelTitle, freeTier, draftsLeft));
        sb.append("\n\n");
        int n = 1;
        for (ContentIdeaEntity idea : ideas.stream().limit(3).toList()) {
            sb.append(ConversionCopy.ideaBlock(n++, idea.getTitle(), idea.getReason(), idea.getFormat(), idea.getSuggestedDay()));
            sb.append('\n');
        }
        sb.append("<i>↓ Выберите идею и нажмите «Пост» — напишу текст</i>");
        return sb.toString().trim();
    }

    public void editSectionMessage(
            long chatId,
            int messageId,
            long requestId,
            List<DeepAnalysisSections.Section> sections,
            int index,
            boolean teaserMode
    ) {
        DeepAnalysisSections.Section section = sections.get(index);
        String text = buildSectionMessage(section, index, sections.size(), teaserMode);
        InlineKeyboardMarkup keyboard = keyboards.analysisSectionsInline(requestId, index, sections.size(), teaserMode);
        messageSender.editText(chatId, messageId, text, keyboard);
    }

    public void sendSectionMessage(
            long chatId,
            long requestId,
            List<DeepAnalysisSections.Section> sections,
            boolean teaserMode
    ) {
        if (sections.isEmpty()) {
            return;
        }
        DeepAnalysisSections.Section section = sections.get(0);
        String text = buildSectionMessage(section, 0, sections.size(), teaserMode);
        InlineKeyboardMarkup keyboard = keyboards.analysisSectionsInline(requestId, 0, sections.size(), teaserMode);
        messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
    }

    private static String buildSectionMessage(
            DeepAnalysisSections.Section section,
            int index,
            int total,
            boolean teaserMode
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧠 <b>Разбор канала</b>");
        sb.append(" <i>(").append(index + 1).append('/').append(total).append(")</i>\n\n");
        sb.append(TgHtml.fromMarkdown(section.body()));
        if (teaserMode && index == 0) {
            sb.append("\n\n<i>Дальше — кнопка «Идеи»: 3 темы и черновик поста.</i>");
        }
        return sb.toString().trim();
    }

    private boolean isTeaserRequest(long requestId) {
        if (!billingProperties.isEnabled()) {
            return false;
        }
        return requestRepository.findById(requestId)
                .map(AnalysisRequestEntity::getType)
                .map(type -> type == RequestType.FREE)
                .orElse(false);
    }

    private RequestContext loadContext(long requestId) {
        AnalysisRequestEntity request = requestRepository.findById(requestId).orElse(null);
        if (request == null) {
            return null;
        }
        ChannelEntity channel = channelRepository.findById(request.getChannelId()).orElse(null);
        if (channel == null) {
            return null;
        }
        boolean freeTier = billingProperties.isEnabled() && request.getType() == RequestType.FREE;
        return new RequestContext(request, channel, freeTier);
    }

    private static long parseRequestId(String callbackData, String prefix) {
        String idStr = callbackData.substring((CallbackData.PREFIX_RESULT + prefix).length());
        return Long.parseLong(idStr);
    }

    private record RequestContext(AnalysisRequestEntity request, ChannelEntity channel, boolean freeTier) {
    }
}
