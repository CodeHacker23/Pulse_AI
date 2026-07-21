package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseAnalysisProperties;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
import org.example.pulse_ai.domain.analysis.GeneratedPostService;
import org.example.pulse_ai.domain.analysis.IdeasGenerationService;
import org.example.pulse_ai.domain.analysis.PollDraftService;
import org.example.pulse_ai.domain.analysis.PostsGenerationService;
import org.example.pulse_ai.domain.analysis.PostDraftService;
import org.example.pulse_ai.domain.request.RequestType;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.ChannelSyncService;
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

    private static final int IDEAS_PER_PAGE = 3;
    private static final int IDEAS_POOL_MAX = 9;

    private final AnalysisSnapshotService snapshotService;
    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final AnalyticsService analyticsService;
    private final ChannelSyncService channelSyncService;
    private final ExternalMetricsService externalMetricsService;
    private final StatsMessageBuilder statsMessageBuilder;
    private final AnalysisChartRenderer chartRenderer;
    private final IdeasGenerationService ideasGenerationService;
    private final PostDraftService postDraftService;
    private final PollDraftService pollDraftService;
    private final PollHandler pollHandler;
    private final GeneratedPostService generatedPostService;
    private final PostsGenerationService postsGenerationService;
    private final PulseAnalysisProperties analysisProperties;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;
    private final UserSessionService sessionService;
    private final PackageRepository packageRepository;

    public boolean handles(String callbackData) {
        return callbackData.startsWith(CallbackData.PREFIX_RESULT);
    }

    public void handle(long chatId, int messageId, String callbackQueryId, String callbackData) {
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "lock:")
                || callbackData.startsWith(CallbackData.PREFIX_RESULT + "draftlock:")
                || callbackData.startsWith(CallbackData.PREFIX_RESULT + "idearegenlock:")) {
            messageSender.answerCallbackWithAlert(callbackQueryId, ConversionCopy.lockAlert());
            if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "draftlock:")) {
                messageSender.sendTextWithInlineSafe(
                        chatId,
                        ConversionCopy.draftPaywall(),
                        keyboards.paymentPackagesInline(
                                packageRepository.findByActiveTrueOrderBySortOrderAsc())
                );
            }
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
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "ideapage:")) {
            handleIdeaPage(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "ideas:")) {
            handleIdeas(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "idearegen:")) {
            handleIdeasRegen(chatId, messageId, callbackData);
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
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "posts:")) {
            handlePosts(chatId, messageId, callbackData);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_RESULT + "postview:")) {
            handlePostView(chatId, messageId, callbackData);
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
        editSectionMessage(chatId, messageId, requestId, sections, 0, false);
    }

    private void handleStats(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "stats:");
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        ChannelEntity channel = refreshMetrics(ctx.channel());
        AnalysisMetrics metrics = analyticsService.analyze(
                channel.getId(),
                ctx.request().getPeriodFrom(),
                ctx.request().getPeriodTo()
        );
        ExternalChannelMetrics external = externalMetricsService.bestMetrics(channel.getUsername());
        int subscribers = channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0;
        if (external.subscribers() != null && external.subscribers() > 0) {
            subscribers = external.subscribers();
        }

        String text = statsMessageBuilder.build(
                requestId, channel.getTitle(), subscribers, metrics, external);
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

        if (DeepAnalysisSections.isIdeasFunnelIndex(index, sections.size())) {
            renderIdeasPage(chatId, messageId, requestId, 0);
            return;
        }

        editSectionMessage(chatId, messageId, requestId, sections, index, false);
    }

    public void openIdeas(long chatId, long requestId) {
        renderIdeasPage(chatId, 0, requestId, 0);
    }

    private void handleIdeas(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "ideas:");
        renderIdeasPage(chatId, messageId, requestId, 0);
    }

    private void handleIdeaPage(long chatId, int messageId, String callbackData) {
        // result:ideapage:{requestId}:{page}
        String tail = callbackData.substring((CallbackData.PREFIX_RESULT + "ideapage:").length());
        int colon = tail.lastIndexOf(':');
        if (colon <= 0) {
            return;
        }
        long requestId = Long.parseLong(tail.substring(0, colon));
        int page = Integer.parseInt(tail.substring(colon + 1));
        renderIdeasPage(chatId, messageId, requestId, page);
    }

    private void renderIdeasPage(long chatId, int messageId, long requestId, int page) {
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        List<ContentIdeaEntity> all = ensureIdeas(ctx);
        if (all.isEmpty()) {
            messageSender.sendTextSafe(chatId, "❌ Не удалось сгенерировать идеи. Попробуйте позже.");
            return;
        }
        List<ContentIdeaEntity> ideas = all.size() > IDEAS_POOL_MAX ? all.subList(0, IDEAS_POOL_MAX) : all;

        int totalPages = (ideas.size() + IDEAS_PER_PAGE - 1) / IDEAS_PER_PAGE;
        int p = Math.max(0, Math.min(page, totalPages - 1));
        int start = p * IDEAS_PER_PAGE;
        int end = Math.min(start + IDEAS_PER_PAGE, ideas.size());
        List<ContentIdeaEntity> pageIdeas = ideas.subList(start, end);

        UserSession session = sessionService.getOrCreate(chatId);
        int draftLimit = billingProperties.draftLimitFor(ctx.request().getType());
        int draftsLeft = billingProperties.isEnabled()
                ? session.draftsRemaining(draftLimit)
                : 99;
        boolean locked = billingProperties.isEnabled() && ctx.freeTier() && draftsLeft <= 0;
        int regenLimit = billingProperties.ideasRegenLimitFor(ctx.request().getType());
        int regensLeft = session.ideasRegensRemaining(ctx.request().getId(), regenLimit);

        String text = buildIdeasMessage(
                ctx.channel().getTitle(), pageIdeas, start, p, totalPages, ctx.freeTier(), draftsLeft, regensLeft);
        List<Long> pageIds = pageIdeas.stream().map(ContentIdeaEntity::getId).toList();
        boolean showBatchPosts = billingProperties.isEnabled() && !ctx.freeTier();
        InlineKeyboardMarkup keyboard = keyboards.ideasPageInline(
                requestId, pageIds, start, p, totalPages, locked, showBatchPosts, true, regensLeft);

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private void handleIdeasRegen(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "idearegen:");
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        UserSession session = sessionService.getOrCreate(chatId);
        int regenLimit = billingProperties.ideasRegenLimitFor(ctx.request().getType());
        if (session.ideasRegensRemaining(requestId, regenLimit) <= 0) {
            messageSender.sendTextSafe(chatId,
                    "🔄 Лимит обновления идей на этот разбор исчерпан. Новый пул — в следующем запросе.");
            return;
        }

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "🔄 Генерирую новые идеи…", null);
        } else {
            messageSender.sendTextSafe(chatId, "🔄 Генерирую новые идеи…");
        }

        try {
            AnalysisMetrics metrics = analyticsService.analyze(
                    ctx.channel().getId(),
                    ctx.request().getPeriodFrom(),
                    ctx.request().getPeriodTo()
            );
            int count = Math.min(IDEAS_POOL_MAX, billingProperties.ideasFor(ctx.request().getType()));
            List<ContentIdeaEntity> ideas = ideasGenerationService.generateIdeas(
                    requestId,
                    ctx.channel().getTitle(),
                    metrics,
                    count,
                    analysisProperties.getLlmTimeoutSeconds()
            );
            snapshotService.replaceIdeas(requestId, ideas);
            session.consumeIdeasRegen(requestId);
        } catch (Exception ex) {
            log.warn("Ideas regen failed for request {}: {}", requestId, ex.getMessage());
            messageSender.sendTextSafe(chatId, "❌ Не удалось обновить идеи. Попробуйте позже.");
            return;
        }

        renderIdeasPage(chatId, messageId, requestId, 0);
    }

    private void handleCharts(long chatId, String callbackData) {
        long requestId = parseRequestId(callbackData, "charts:");
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }

        ChannelEntity channel = refreshMetrics(ctx.channel());
        AnalysisMetrics metrics = analyticsService.analyze(
                channel.getId(),
                ctx.request().getPeriodFrom(),
                ctx.request().getPeriodTo()
        );
        AnalysisChartPack charts = chartRenderer.render(channel.getTitle(), metrics);

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
        int draftLimit = billingProperties.draftLimitFor(ctx.request().getType());
        if (billingProperties.isEnabled() && !session.tryConsumeFreeDraft(ideaId, draftLimit)) {
            String paywall = ConversionCopy.draftPaywall();
            InlineKeyboardMarkup keyboard = keyboards.paymentPackagesInline(
                    packageRepository.findByActiveTrueOrderBySortOrderAsc());
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

        // Идея-опрос → нативный Telegram Poll, а не текстовый черновик.
        if (PollDraftService.isPollFormat(idea.getFormat())) {
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, "⏳ Собираю опрос…", null);
            }
            PollDraftService.PollDraft poll = pollDraftService.generate(ctx.channel().getTitle(), idea);
            GeneratedPostEntity savedPoll = generatedPostService.savePollDraft(
                    requestId, idea, poll.question(), poll.options(), true);
            session.setPostId(savedPoll.getId());
            session.setRequestId(requestId);
            pollHandler.showPollBuilder(chatId, messageId, savedPoll, requestId);
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

        GeneratedPostEntity savedPost = generatedPostService.saveRegeneratedDraft(requestId, idea, draft);
        session.setPostId(savedPost.getId());
        session.setRequestId(requestId);

        int draftsLeft = billingProperties.isEnabled()
                ? session.draftsRemaining(draftLimit)
                : 99;
        int sectionTotal = Math.max(snapshotService.getSections(requestId).size(), DeepAnalysisSections.sectionCount());
        boolean teaser = isTeaserRequest(requestId);

        String text = ConversionCopy.draftHeader(idea.getTitle())
                + "\n\n"
                + TgHtml.fromMarkdown(draft)
                + footerBlock(ctx.freeTier(), draftsLeft);
        InlineKeyboardMarkup keyboard = keyboards.draftResultInline(
                requestId, ideaId, savedPost.getId(), sectionTotal, teaser, ctx.freeTier(), draftsLeft);

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private void handlePosts(long chatId, int messageId, String callbackData) {
        long requestId = parseRequestId(callbackData, "posts:");
        RequestContext ctx = loadContext(requestId);
        if (ctx == null) {
            return;
        }
        if (ctx.freeTier()) {
            messageSender.sendTextWithInlineSafe(
                    chatId,
                    ConversionCopy.draftPaywall(),
                    keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc())
            );
            return;
        }

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ <b>Генерирую 7 постов…</b>\n\n<i>~1 минута</i>", null);
        }

        List<ContentIdeaEntity> ideas = ensureIdeas(ctx);
        AnalysisMetrics metrics = analyticsService.analyze(
                ctx.channel().getId(),
                ctx.request().getPeriodFrom(),
                ctx.request().getPeriodTo()
        );
        int postCount = billingProperties.getPaidDraftLimit();
        List<GeneratedPostEntity> posts = postsGenerationService.generatePosts(
                requestId,
                ctx.channel().getTitle(),
                ideas,
                metrics,
                postCount,
                analysisProperties.getLlmTimeoutSeconds()
        );

        StringBuilder text = new StringBuilder();
        text.append("📝 <b>7 готовых постов — «").append(TgHtml.esc(ctx.channel().getTitle())).append("»</b>\n\n");
        int n = 1;
        for (GeneratedPostEntity post : posts) {
            String preview = generatedPostService.latestText(post);
            if (preview.length() > 120) {
                preview = preview.substring(0, 117) + "…";
            }
            text.append(n++).append(". ").append(TgHtml.esc(preview)).append("\n\n");
        }
        text.append("<i>Нажмите «Пост N» — откроется полный текст с публикацией.</i>");

        InlineKeyboardMarkup keyboard = keyboards.generatedPostsInline(requestId, posts);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text.toString().trim(), keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text.toString().trim(), keyboard);
        }
    }

    private void handlePostView(long chatId, int messageId, String callbackData) {
        long postId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_RESULT + "postview:").length()));
        GeneratedPostEntity post = generatedPostService.findById(postId).orElse(null);
        if (post == null) {
            messageSender.sendTextSafe(chatId, "❌ Пост не найден.");
            return;
        }
        RequestContext ctx = loadContext(post.getRequestId());
        if (ctx == null) {
            return;
        }
        ContentIdeaEntity idea = snapshotService.getIdeas(post.getRequestId()).stream()
                .filter(i -> i.getId().equals(post.getIdeaId()))
                .findFirst()
                .orElse(null);
        String ideaTitle = idea != null ? idea.getTitle() : "пост";
        String draft = generatedPostService.latestText(post);
        UserSession session = sessionService.getOrCreate(chatId);
        session.setPostId(postId);
        session.setRequestId(post.getRequestId());

        int sectionTotal = Math.max(snapshotService.getSections(post.getRequestId()).size(), DeepAnalysisSections.sectionCount());
        boolean teaser = isTeaserRequest(post.getRequestId());
        String text = ConversionCopy.draftHeader(ideaTitle) + "\n\n" + TgHtml.fromMarkdown(draft);
        InlineKeyboardMarkup keyboard = keyboards.draftResultInline(
                post.getRequestId(),
                post.getIdeaId(),
                postId,
                sectionTotal,
                teaser,
                ctx.freeTier(),
                99);

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
                    """ + org.example.pulse_ai.text.SalesCopy.upsellAfterFreeAnalysis();
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
            int count = Math.min(IDEAS_POOL_MAX, billingProperties.ideasFor(ctx.request().getType()));
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

    private String buildIdeasMessage(
            String channelTitle,
            List<ContentIdeaEntity> pageIdeas,
            int globalStart,
            int page,
            int totalPages,
            boolean freeTier,
            int draftsLeft,
            int ideasRegensLeft
    ) {
        StringBuilder sb = new StringBuilder(ConversionCopy.ideasIntro(channelTitle, freeTier, draftsLeft));
        if (ideasRegensLeft > 0) {
            sb.append("\n<i>Не зашли идеи? «🔄 Новые идеи» — без списания запроса (осталось ")
                    .append(ideasRegensLeft).append(").</i>");
        }
        sb.append("\n\n");
        int n = globalStart + 1;
        for (ContentIdeaEntity idea : pageIdeas) {
            sb.append(ConversionCopy.ideaBlock(n++, idea.getTitle(), idea.getReason(), idea.getFormat(), idea.getSuggestedDay()));
            sb.append('\n');
        }
        if (totalPages > 1) {
            sb.append("<i>Стр. ").append(page + 1).append('/').append(totalPages)
                    .append(" · листайте стрелками ниже, жмите «Пост N» — напишу текст.</i>");
        } else {
            sb.append("<i>↓ Выберите идею и нажмите «Пост» — напишу текст</i>");
        }
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
        return sb.toString().trim();
    }

    private boolean isTeaserRequest(long requestId) {
        return false;
    }

    /** Перед показом статистики — свежий sync + чистка мусорных просмотров. */
    private ChannelEntity refreshMetrics(ChannelEntity channel) {
        channelSyncService.syncForAnalysis(channel);
        return channelRepository.findById(channel.getId()).orElse(channel);
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
