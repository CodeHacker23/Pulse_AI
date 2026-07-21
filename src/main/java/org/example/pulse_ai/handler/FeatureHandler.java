package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.analysis.AnalysisSnapshotService;
import org.example.pulse_ai.domain.analysis.CompetitorAnalysisService;
import org.example.pulse_ai.domain.analysis.HardAuditService;
import org.example.pulse_ai.domain.analysis.PostAuditService;
import org.example.pulse_ai.domain.analysis.SellingPostService;
import org.example.pulse_ai.domain.analysis.WeeklyDigestService;
import org.example.pulse_ai.stats.ChannelSyncService;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.stats.AnalyticsService;
import org.example.pulse_ai.stats.model.AnalysisMetrics;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.SalesCopy;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureHandler {

    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final EntitlementService entitlementService;
    private final PostAuditService postAuditService;
    private final HardAuditService hardAuditService;
    private final CompetitorAnalysisService competitorAnalysisService;
    private final WeeklyDigestService weeklyDigestService;
    private final SellingPostService sellingPostService;
    private final ChannelSyncService channelSyncService;
    private final UserService userService;
    private final UserSessionService sessionService;
    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final AnalyticsService analyticsService;
    private final AnalysisSnapshotService snapshotService;
    private final PackageRepository packageRepository;

    public void handle(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        messageSender.answerCallback(callbackQueryId);

        if (callbackData.equals(CallbackData.FEAT_POST_AUDIT)) {
            if (!entitlementService.hasAccess(user.getId(), PerkType.POST_AUDIT)) {
                showPaywall(chatId, messageId);
                return;
            }
            String text = SalesCopy.postAuditIntro();
            InlineKeyboardMarkup kb = keyboards.backToMainInline();
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, text, kb);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, text, kb);
            }
            return;
        }

        if (callbackData.equals(CallbackData.FEAT_COMPETITOR)) {
            startCompetitor(chatId, messageId, user);
            return;
        }

        if (callbackData.equals(CallbackData.FEAT_DIGEST)) {
            handleDigest(chatId, messageId, user);
            return;
        }

        if (callbackData.equals(CallbackData.FEAT_SELLING)) {
            startSelling(chatId, messageId, user);
            return;
        }

        if (callbackData.startsWith(CallbackData.PREFIX_FEAT + "hard:")) {
            long requestId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_FEAT + "hard:").length()));
            handleHardAudit(chatId, messageId, user, requestId);
        }
    }

    private void startCompetitor(long chatId, int messageId, UserEntity user) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.COMPETITOR)) {
            showPaywall(chatId, messageId);
            return;
        }
        if (userService.findActiveChannel(user).isEmpty()) {
            String text = "Сначала подключите свой канал — потом сравню его с конкурентом.\n\n"
                    + "Пришлите ссылку на свой канал в чат.";
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, text, keyboards.backToMainInline());
            } else {
                messageSender.sendTextWithInlineSafe(chatId, text, keyboards.backToMainInline());
            }
            return;
        }
        sessionService.setState(chatId, BotState.COMPETITOR_INPUT);
        String text = """
                ⚔️ <b>Анализ конкурента</b>

                Пришлите ссылку на <b>канал конкурента</b> (или @username).
                Сравню его с вашим каналом: где вы сильнее, где отстаёте и что перенять.""";
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboards.backToMainInline());
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboards.backToMainInline());
        }
    }

    private void startSelling(long chatId, int messageId, UserEntity user) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.SELLING)) {
            showPaywall(chatId, messageId);
            return;
        }
        if (userService.findActiveChannel(user).isEmpty()) {
            String text = "Подключите свой канал — напишу продающий пост в вашем стиле.";
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, text, keyboards.backToMainInline());
            } else {
                messageSender.sendTextWithInlineSafe(chatId, text, keyboards.backToMainInline());
            }
            return;
        }
        sessionService.setState(chatId, BotState.SELLING_INPUT);
        String text = """
                💰 <b>Продающий пост</b>

                Опишите одним сообщением, <b>что продаём</b>:
                • продукт/услуга и для кого
                • ключевая выгода
                • цена и реальные условия (если есть дедлайн — только настоящий)

                Напишу честный пост под ваш стиль: боль → выгода → доказательство → оффер → призыв.""";
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboards.backToMainInline());
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboards.backToMainInline());
        }
    }

    public void handleSellingInput(long chatId, UserEntity user, String offerBrief) {
        ChannelEntity myChannel = userService.findActiveChannel(user).orElse(null);
        if (myChannel == null) {
            sessionService.resetToMainMenu(chatId);
            messageSender.sendTextSafe(chatId, "Сначала подключите свой канал.");
            return;
        }
        if (!entitlementService.tryConsume(user.getId(), PerkType.SELLING)) {
            sessionService.resetToMainMenu(chatId);
            messageSender.sendTextWithInlineSafe(chatId, SalesCopy.perkLockedUpsell(),
                    keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc()));
            return;
        }
        messageSender.sendTextSafe(chatId, "💰 Пишу продающий пост…");
        String post = sellingPostService.generate(myChannel, offerBrief);
        sessionService.resetToMainMenu(chatId);
        String text = "💰 <b>Продающий пост</b>\n\n" + TgHtml.fromMarkdown(post);
        messageSender.sendTextWithInlineSafe(chatId, text, keyboards.featureHubInline());
    }

    private void handleDigest(long chatId, int messageId, UserEntity user) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.DIGEST)) {
            showPaywall(chatId, messageId);
            return;
        }
        ChannelEntity channel = userService.findActiveChannel(user).orElse(null);
        if (channel == null) {
            String text = "Подключите свой канал — и я соберу дайджест недели по нему.";
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, text, keyboards.backToMainInline());
            } else {
                messageSender.sendTextWithInlineSafe(chatId, text, keyboards.backToMainInline());
            }
            return;
        }
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "📬 Собираю дайджест недели…", null);
        }
        channelSyncService.syncForAnalysis(channel);
        String digest = weeklyDigestService.buildDigest(channel);
        messageSender.sendTextWithInlineSafe(chatId, TgHtml.fromMarkdown(digest), keyboards.featureHubInline());
    }

    public void handleCompetitorInput(long chatId, UserEntity user, String rawInput) {
        ChannelEntity myChannel = userService.findActiveChannel(user).orElse(null);
        if (myChannel == null) {
            sessionService.resetToMainMenu(chatId);
            messageSender.sendTextSafe(chatId, "Сначала подключите свой канал.");
            return;
        }
        if (!entitlementService.tryConsume(user.getId(), PerkType.COMPETITOR)) {
            sessionService.resetToMainMenu(chatId);
            messageSender.sendTextWithInlineSafe(chatId, SalesCopy.perkLockedUpsell(),
                    keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc()));
            return;
        }

        messageSender.sendTextSafe(chatId, "⚔️ Сравниваю каналы… это займёт до минуты.");
        CompetitorAnalysisService.CompetitorResult result =
                competitorAnalysisService.compare(myChannel, rawInput);
        sessionService.resetToMainMenu(chatId);

        if (!result.success()) {
            messageSender.sendTextWithInlineSafe(chatId,
                    "❌ " + TgHtml.esc(result.error()), keyboards.featureHubInline());
            return;
        }
        String text = "⚔️ <b>" + TgHtml.esc(myChannel.getTitle()) + "</b> vs <b>"
                + TgHtml.esc(result.competitorTitle()) + "</b>\n\n"
                + TgHtml.fromMarkdown(result.text());
        messageSender.sendTextWithInlineSafe(chatId, text, keyboards.featureHubInline());
    }

    public void handleForwardedPost(long chatId, UserEntity user, Message message) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.POST_AUDIT)) {
            messageSender.sendTextWithInlineSafe(chatId, SalesCopy.perkLockedUpsell(),
                    keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc()));
            return;
        }

        String postText = extractPostText(message);
        if (postText.isBlank()) {
            messageSender.sendText(chatId, "Перешлите пост с текстом — разберу его.");
            return;
        }

        String channelTitle = message.getForwardFromChat() != null
                ? message.getForwardFromChat().getTitle()
                : "канал";

        messageSender.sendTextSafe(chatId, SalesCopy.postAuditGenerating());
        String audit = postAuditService.audit(channelTitle, postText, null, null);
        String text = "🔬 <b>Разбор поста</b>\n\n" + TgHtml.fromMarkdown(audit.trim());
        messageSender.sendTextWithInlineSafe(chatId, text, keyboards.featureHubInline());
    }

    private void handleHardAudit(long chatId, int messageId, UserEntity user, long requestId) {
        if (!entitlementService.hasAccess(user.getId(), PerkType.HARD_AUDIT)) {
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, SalesCopy.hardAuditLocked(),
                        keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc()));
            } else {
                messageSender.sendTextWithInlineSafe(chatId, SalesCopy.hardAuditLocked(),
                        keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc()));
            }
            return;
        }
        if (!entitlementService.tryConsume(user.getId(), PerkType.HARD_AUDIT)) {
            messageSender.sendTextWithInlineSafe(chatId, SalesCopy.hardAuditLocked(),
                    keyboards.paymentPackagesInline(packageRepository.findByActiveTrueOrderBySortOrderAsc()));
            return;
        }

        AnalysisRequestEntity request = requestRepository.findById(requestId).orElse(null);
        if (request == null || !request.getUserId().equals(user.getId())) {
            messageSender.sendTextSafe(chatId, "❌ Отчёт не найден.");
            return;
        }
        ChannelEntity channel = channelRepository.findById(request.getChannelId()).orElse(null);
        if (channel == null) {
            return;
        }

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, SalesCopy.hardAuditGenerating(), null);
        }

        channelSyncService.syncForAnalysis(channel);
        channel = channelRepository.findById(channel.getId()).orElse(channel);

        AnalysisMetrics metrics = analyticsService.analyze(
                channel.getId(), request.getPeriodFrom(), request.getPeriodTo());
        int subs = channel.getSubscriberCount() != null ? channel.getSubscriberCount() : 0;
        String audit = hardAuditService.audit(
                channel.getId(), channel.getTitle(), metrics, subs,
                request.getPeriodFrom(), request.getPeriodTo());

        String text = "🔥 <b>Жёсткий аудит — " + TgHtml.esc(channel.getTitle()) + "</b>\n\n"
                + TgHtml.fromMarkdown(audit.trim());
        int sectionTotal = Math.max(snapshotService.getSections(requestId).size(), 5);
        InlineKeyboardMarkup keyboard = keyboards.analysisSectionsInline(requestId, 0, sectionTotal, false);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private void showPaywall(long chatId, int messageId) {
        InlineKeyboardMarkup kb = keyboards.paymentPackagesInline(
                packageRepository.findByActiveTrueOrderBySortOrderAsc());
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, SalesCopy.perkLockedUpsell(), kb);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, SalesCopy.perkLockedUpsell(), kb);
        }
    }

    private static String extractPostText(Message message) {
        if (message.getText() != null && !message.getText().isBlank()) {
            return message.getText().trim();
        }
        if (message.getCaption() != null && !message.getCaption().isBlank()) {
            return message.getCaption().trim();
        }
        return "";
    }
}
