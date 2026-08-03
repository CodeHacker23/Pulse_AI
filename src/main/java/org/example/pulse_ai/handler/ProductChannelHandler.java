package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.domain.product.ProductChannelReportService;
import org.example.pulse_ai.domain.product.ProductChannelService;
import org.example.pulse_ai.domain.product.ProductPostRubric;
import org.example.pulse_ai.domain.product.ProductReleaseService;
import org.example.pulse_ai.domain.product.ProductStoryService;
import org.example.pulse_ai.domain.product.ProductStyleLearnerService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.example.pulse_ai.persistence.entity.ProductReleaseEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ProductChannelHandler {

    private final PulseProductChannelProperties properties;
    private final ProductChannelService productChannelService;
    private final ProductStyleLearnerService styleLearner;
    private final ProductChannelReportService reportService;
    private final ProductReleaseService releaseService;
    private final ProductStoryService storyService;
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
                    Ваш Telegram ID: <code>%d</code>

                    В <code>application-local.yaml</code> должно быть:
                    <code>pulse.product.owner-telegram-ids: [%d]</code>
                    (это ваш user id, не id канала)

                    Перезапустите бота после правки конфига.""".formatted(
                    user.getTelegramId(), user.getTelegramId()));
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
                        2️⃣ «📖 Сюжет канала» — серия постов-знакомство (не один вброс)
                        3️⃣ «🛠 Релизы» — факты апдейтов → Changelog
                        4️⃣ Рубрика — черновик → правка → публикация
                        5️⃣ «📊 Отчёт» — статистика""".formatted(channelLine),
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
        if (callbackData.equals(CallbackData.PRODUCT_RELEASES)) {
            showReleases(chatId, messageId);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_RELEASE_ADD)) {
            startAddRelease(chatId, messageId);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_RELEASE_LATEST)) {
            generateLatestChangelog(chatId, messageId, user);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_STORY)
                || callbackData.equals(CallbackData.PRODUCT_STORY_SHOW)) {
            showStory(chatId, messageId);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_STORY_BUILD)) {
            buildStory(chatId, messageId, user);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_STORY_NEXT)) {
            publishStoryNext(chatId, messageId, user);
            return;
        }
        if (callbackData.equals(CallbackData.PRODUCT_STORY_START)) {
            startStoryArc(chatId, messageId, user);
            return;
        }
        if (callbackData.startsWith(CallbackData.PREFIX_PRODUCT + "relpost:")) {
            long releaseId = Long.parseLong(callbackData.substring((CallbackData.PREFIX_PRODUCT + "relpost:").length()));
            generateFromRelease(chatId, messageId, user, releaseId);
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

    public void handleReleaseInput(long chatId, UserEntity user, String text) {
        try {
            ProductReleaseEntity saved = releaseService.addFromRawText(text);
            sessionService.getOrCreate(chatId).setState(BotState.MAIN_MENU);
            messageSender.sendTextWithInlineSafe(chatId, """
                    ✅ <b>Релиз сохранён</b>

                    <code>%s</code> — %s
                    Статус: READY

                    Можешь сразу собрать патчноут в канал.""".formatted(
                    TgHtml.esc(saved.getVersion()),
                    TgHtml.esc(saved.getTitle())),
                    keyboards.productReleasesInline());
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "❌ " + TgHtml.esc(ex.getMessage()));
        }
    }

    private void showStory(long chatId, int messageId) {
        var arcOpt = storyService.activeArc();
        boolean has = arcOpt.isPresent();
        String body;
        if (has) {
            body = storyService.formatPlan(arcOpt.get().getId());
        } else {
            body = """
                    📖 <b>Сюжет канала</b>

                    Не один пост «мы крутые», а серия из 6 эпизодов:
                    боль админа → ясность → ритуал → ассистент → умный рост → приглашение.

                    Бот составит план и тексты, потом можно:
                    • выпустить следующий эпизод вручную
                    • или запустить арку: 1 сейчас, остальные по одному в день в 11:00 МСК""";
        }
        InlineKeyboardMarkup kb = keyboards.productStoryInline(has);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, body, kb);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, body, kb);
        }
    }

    private void buildStory(long chatId, int messageId, UserEntity user) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId,
                    "⏳ <b>Пишу сюжетную арку…</b>\n\n6 эпизодов, связный сюжет. ~1–2 минуты.", null);
        }
        try {
            var arc = storyService.createIntroArc(user.getTelegramId());
            String plan = storyService.formatPlan(arc.getId());
            String text = "✅ <b>План готов</b>\n\n" + plan
                    + "\n\nДальше: опубликовать следующий эпизод или запустить всю арку.";
            messageSender.sendTextWithInlineSafe(chatId, text, keyboards.productStoryInline(true));
        } catch (Exception ex) {
            messageSender.sendTextWithInlineSafe(chatId,
                    "❌ Не удалось собрать арку: " + TgHtml.esc(ex.getMessage()),
                    keyboards.productStoryInline(false));
        }
    }

    private void publishStoryNext(long chatId, int messageId, UserEntity user) {
        var arc = storyService.activeArc().orElse(null);
        if (arc == null) {
            messageSender.sendTextWithInlineSafe(chatId, "Сначала собери сюжетный план.",
                    keyboards.productStoryInline(false));
            return;
        }
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ Публикую следующий эпизод…", null);
        }
        var r = storyService.publishNext(arc.getId(), user.getTelegramId());
        if (!r.ok()) {
            messageSender.sendTextWithInlineSafe(chatId, "❌ " + TgHtml.esc(r.error()),
                    keyboards.productStoryInline(true));
            return;
        }
        String ok = "✅ <b>Эпизод в канале</b>\n" + TgHtml.esc(r.beat().getTitle());
        if (r.link() != null) {
            ok += "\n🔗 <a href=\"" + TgHtml.esc(r.link()) + "\">Открыть</a>";
        }
        messageSender.sendTextWithInlineSafe(chatId, ok, keyboards.productStoryInline(true));
    }

    private void startStoryArc(long chatId, int messageId, UserEntity user) {
        var arc = storyService.activeArc().orElse(null);
        if (arc == null) {
            messageSender.sendTextWithInlineSafe(chatId, "Сначала собери сюжетный план.",
                    keyboards.productStoryInline(false));
            return;
        }
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ Запускаю арку…", null);
        }
        var r = storyService.startArcDaily(arc.getId(), user.getTelegramId());
        if (!r.ok()) {
            messageSender.sendTextWithInlineSafe(chatId, "❌ " + TgHtml.esc(r.error()),
                    keyboards.productStoryInline(true));
            return;
        }
        messageSender.sendTextWithInlineSafe(chatId, """
                🎬 <b>Арка запущена</b>

                Сейчас в канале: %d эпизод
                В очереди на следующие дни (11:00 МСК): %d

                Сюжет пойдёт сам — можно не вбрасывать всё за раз.""".formatted(
                r.publishedNow(), r.scheduled()),
                keyboards.productStoryInline(true));
    }

    private void showReleases(long chatId, int messageId) {
        var list = releaseService.recent(12);
        StringBuilder sb = new StringBuilder("🛠 <b>Реестр релизов</b>\n\n");
        if (list.isEmpty()) {
            sb.append("Пока пусто. Добавь апдейт — бот будет писать Changelog из фактов.\n");
        } else {
            for (ProductReleaseEntity r : list) {
                sb.append("<b>").append(TgHtml.esc(r.getVersion())).append("</b> — ")
                        .append(TgHtml.esc(r.getTitle()))
                        .append(" · ").append(TgHtml.esc(r.getStatus()))
                        .append(" · ").append(TgHtml.esc(r.getCategory()))
                        .append('\n');
            }
            sb.append("\nНажми релиз ниже → готовый патчноут в превью.");
        }
        List<List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>> rows =
                new ArrayList<>();
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton> row =
                new ArrayList<>();
        for (ProductReleaseEntity r : list) {
            if (!"READY".equals(r.getStatus()) && !"DRAFT".equals(r.getStatus())) {
                continue;
            }
            row.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text(r.getVersion())
                    .callbackData(CallbackData.PREFIX_PRODUCT + "relpost:" + r.getId())
                    .build());
            if (row.size() == 3) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                        .text("➕ Добавить апдейт")
                        .callbackData(CallbackData.PRODUCT_RELEASE_ADD)
                        .build(),
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                        .text("📋 Latest READY")
                        .callbackData(CallbackData.PRODUCT_RELEASE_LATEST)
                        .build()
        ));
        rows.add(List.of(
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                        .text("◀️ Назад")
                        .callbackData(CallbackData.PRODUCT_MENU)
                        .build()
        ));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(rows);
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, sb.toString().trim(), keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, sb.toString().trim(), keyboard);
        }
    }

    private void startAddRelease(long chatId, int messageId) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.PRODUCT_RELEASE_ADD);
        String text = """
                ➕ <b>Новый апдейт в реестр</b>

                Пришли одним сообщением:
                <code>0.4.3 | Короткий заголовок</code>
                <code>▪️Что сделали</code>
                <code>▪️Что пофиксили</code>
                <code>▪️Что тестируем</code>

                Или просто список буллетов — версию проставим сами.""";
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboards.productReleasesInline());
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboards.productReleasesInline());
        }
    }

    private void generateLatestChangelog(long chatId, int messageId, UserEntity user) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ Собираю патчноут из реестра…", null);
        }
        ProductChannelPostEntity post = productChannelService.generateDraft(
                ProductPostRubric.CHANGELOG, user.getTelegramId(), null);
        UserSession session = sessionService.getOrCreate(chatId);
        session.setProductPostId(post.getId());
        session.setState(BotState.PRODUCT_PREVIEW);
        showPreview(chatId, messageId, post.getId(), post.getDraftText());
    }

    private void generateFromRelease(long chatId, int messageId, UserEntity user, long releaseId) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, "⏳ Собираю патчноут…", null);
        }
        ProductChannelPostEntity post = productChannelService.generateChangelogFromRelease(
                releaseId, user.getTelegramId());
        UserSession session = sessionService.getOrCreate(chatId);
        session.setProductPostId(post.getId());
        session.setState(BotState.PRODUCT_PREVIEW);
        showPreview(chatId, messageId, post.getId(), post.getDraftText());
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
