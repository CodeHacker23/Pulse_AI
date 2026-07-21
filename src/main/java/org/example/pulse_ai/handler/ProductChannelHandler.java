package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.domain.product.ProductChannelReportService;
import org.example.pulse_ai.domain.product.ProductChannelService;
import org.example.pulse_ai.domain.product.ProductPostRubric;
import org.example.pulse_ai.domain.product.ProductStyleLearnerService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@Component
@RequiredArgsConstructor
public class ProductChannelHandler {

    private final PulseProductChannelProperties properties;
    private final ProductChannelService productChannelService;
    private final ProductStyleLearnerService styleLearner;
    private final ProductChannelReportService reportService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public boolean isOwner(long telegramUserId) {
        return properties.isEnabled() && properties.isOwner(telegramUserId);
    }

    public void showMenu(long chatId, UserEntity user) {
        if (!isOwner(user.getTelegramId())) {
            messageSender.sendText(chatId, """
                    📢 <b>Канал Pulse AI</b>

                    Эта команда только для владельца продукта.
                    Ваш Telegram ID: <code>%d</code> — добавьте в <code>owner-telegram-ids</code> в конфиге.

                    Для анализа своего канала перешлите пост из канала (бот должен быть админом) или пришлите ссылку.""".formatted(
                    user.getTelegramId()));
            return;
        }
        ProductChannelService.ChannelReadiness readiness = productChannelService.checkChannel();
        String channelLine = readiness.ready()
                ? "✅ Бот админ — можно публиковать"
                : "⚠️ " + TgHtml.esc(readiness.message());

        messageSender.sendTextWithInline(
                chatId,
                """
                        📢 <b>Ваш канал-витрина Pulse AI</b>

                        %s

                        <b>Зачем канал:</b> люди видят продукт вживую — демо, акции, инсайты.
                        <b>Не live:</b> async-посты (утренний бриф, новости, фичи бота).

                        1️⃣ «🔄 Учиться с каналов» — бот смотрит референсы и запоминает стиль
                        2️⃣ Выберите рубрику — черновик → правка → публикация
                        3️⃣ «📊 Отчёт» — статистика канала и публикаций""".formatted(channelLine),
                keyboards.productMenuInline()
        );
    }

    /** Утренний черновик в ЛС владельца (из scheduler). */
    public void deliverDraftToOwner(long ownerChatId, ProductChannelPostEntity post) {
        UserSession session = sessionService.getOrCreate(ownerChatId);
        session.setProductPostId(post.getId());
        session.setState(BotState.PRODUCT_PREVIEW);

        String text = """
                ☀️ <b>Утренний черновик для канала</b>

                <i>%s</i>

                %s

                Опубликовать в канал?""".formatted(
                TgHtml.esc(post.getRubric().label()),
                TgHtml.esc(post.getDraftText()));

        messageSender.sendTextWithInlineSafe(ownerChatId, text.trim(), keyboards.productPreviewInline(post.getId()));
    }

    public void handle(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        if (!isOwner(user.getTelegramId())) {
            messageSender.answerCallbackWithAlert(callbackQueryId, "Недоступно.");
            return;
        }
        messageSender.answerCallback(callbackQueryId);

        if (callbackData.equals(CallbackData.PRODUCT_MENU)) {
            showMenu(chatId, user);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_SYNC)) {
            handleSync(chatId, messageId);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_REPORT)) {
            handleReport(chatId, messageId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PRODUCT + "gen:")) {
            String rubricCode = callbackData.substring((CallbackData.PREFIX_PRODUCT + "gen:").length());
            generate(chatId, messageId, user, ProductPostRubric.valueOf(rubricCode));
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PRODUCT + "confirm:")) {
            long postId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_PRODUCT + "confirm:").length()));
            confirmPublish(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PRODUCT + "edit:")) {
            long postId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_PRODUCT + "edit:").length()));
            startEdit(chatId, messageId, user, postId);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PRODUCT + "cancel:")) {
            long postId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_PRODUCT + "cancel:").length()));
            showPreview(chatId, messageId, postId, null);
        }
    }

    public void handleEditedText(long chatId, UserEntity user, String text) {
        UserSession session = sessionService.getOrCreate(chatId);
        Long postId = session.getProductPostId();
        if (postId == null) {
            sessionService.resetToMainMenu(chatId);
            return;
        }
        productChannelService.updateDraftText(postId, text);
        session.setProductEditDraft(text.trim());
        session.setState(BotState.PRODUCT_PREVIEW);
        showPreview(chatId, 0, postId, text.trim());
    }

    private void handleSync(long chatId, int messageId) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "🔄 <b>Учусь с референс-каналов…</b>\n\n<i>~1 минута</i>", null);
        }
        ProductStyleLearnerService.SyncResult result = styleLearner.syncFromReferences();
        String text = result.success()
                ? "✅ <b>Стиль обновлён</b>\n\n" + TgHtml.esc(result.message())
                : "❌ " + TgHtml.esc(result.message());
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboards.productMenuInline());
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboards.productMenuInline());
        }
    }

    private void handleReport(long chatId, int messageId) {
        String report = reportService.buildOwnerReport();
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, report, keyboards.productMenuInline());
        } else {
            messageSender.sendTextWithInlineSafe(chatId, report, keyboards.productMenuInline());
        }
    }

    private void generate(long chatId, int messageId, UserEntity user, ProductPostRubric rubric) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ <b>Пишу пост…</b>\n\n<i>" + TgHtml.esc(rubric.hint()) + "</i>", null);
        }
        ProductChannelPostEntity post = productChannelService.generateDraft(rubric, user.getTelegramId(), null);
        UserSession session = sessionService.getOrCreate(chatId);
        session.setProductPostId(post.getId());
        session.setState(BotState.PRODUCT_PREVIEW);
        showPreview(chatId, messageId, post.getId(), post.getDraftText());
    }

    private void showPreview(long chatId, int messageId, long postId, String overrideText) {
        ProductChannelPostEntity post = productChannelService.findById(postId).orElse(null);
        if (post == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        String text = overrideText != null ? overrideText : post.getDraftText();
        String preview = """
                📤 <b>Превью для канала</b>
                <i>%s</i>

                %s

                Опубликовать?""".formatted(
                TgHtml.esc(post.getRubric().label()),
                TgHtml.esc(text));

        InlineKeyboardMarkup keyboard = keyboards.productPreviewInline(postId);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, preview.trim(), keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, preview.trim(), keyboard);
        }
    }

    private void startEdit(long chatId, int messageId, UserEntity user, long postId) {
        ProductChannelPostEntity post = productChannelService.findById(postId).orElse(null);
        if (post == null) {
            return;
        }
        UserSession session = sessionService.getOrCreate(chatId);
        session.setProductPostId(postId);
        session.setState(BotState.PRODUCT_EDIT);

        String text = """
                ✏️ <b>Правка поста</b>

                %s

                <i>Отправьте новый текст одним сообщением.</i>""".formatted(TgHtml.esc(post.getDraftText()));

        InlineKeyboardMarkup keyboard = keyboards.productEditInline(postId);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }

    private void confirmPublish(long chatId, int messageId, UserEntity user, long postId) {
        UserSession session = sessionService.getOrCreate(chatId);
        ProductChannelPostEntity post = productChannelService.findById(postId).orElse(null);
        if (post == null) {
            messageSender.sendTextSafe(chatId, "❌ Черновик не найден.");
            return;
        }
        String finalText = session.getProductEditDraft() != null
                ? session.getProductEditDraft()
                : post.getDraftText();

        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ Публикую в канал…", null);
        }

        ProductChannelService.PublishOutcome outcome = productChannelService.publish(postId, finalText);
        session.clearProductFlow();
        session.setState(BotState.MAIN_MENU);

        if (!outcome.success()) {
            String error = """
                    ❌ <b>Не удалось опубликовать</b>

                    %s""".formatted(TgHtml.esc(outcome.error()));
            InlineKeyboardMarkup keyboard = keyboards.productPreviewInline(postId);
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, error, keyboard);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, error, keyboard);
            }
            return;
        }

        String success = "✅ <b>Опубликовано в канал!</b>";
        if (outcome.link() != null) {
            success += "\n🔗 <a href=\"" + TgHtml.esc(outcome.link()) + "\">Открыть пост</a>";
        }
        messageSender.sendTextWithInlineSafe(chatId, success, keyboards.productMenuInline());
    }
}
