package org.example.pulse_ai.keyboard;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.config.PulseImageProperties;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.handler.CallbackData;
import org.example.pulse_ai.handler.MenuText;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KeyboardFactory {

    private final PulseBillingProperties billingProperties;
    private final PulseImageProperties imageProperties;

    public ReplyKeyboardMarkup mainMenuKeyboard() {
        return mainMenuKeyboard(billingProperties.isEnabled());
    }
    public ReplyKeyboardMarkup mainMenuKeyboard(boolean billingEnabled) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(MenuText.BTN_CONTENT));
        row1.add(new KeyboardButton(MenuText.BTN_ANALYTICS));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(MenuText.BTN_SCHEDULED));
        row2.add(new KeyboardButton(MenuText.BTN_MANAGER));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton(MenuText.BTN_SETTINGS));
        row3.add(new KeyboardButton(MenuText.BTN_HELP));

        List<KeyboardRow> rows = new ArrayList<>(List.of(row1, row2, row3));
        if (billingEnabled) {
            KeyboardRow row4 = new KeyboardRow();
            row4.add(new KeyboardButton(MenuText.BTN_BUY));
            rows.add(row4);
        }

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(rows);
        markup.setResizeKeyboard(true);
        markup.setInputFieldPlaceholder("Или ссылка на канал…");
        return markup;
    }

    public InlineKeyboardMarkup mainMenuInline(int historyCount) {
        return mainMenuInline(historyCount, billingProperties.isEnabled());
    }

    public ReplyKeyboardMarkup startMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(MenuText.BTN_HOW));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(MenuText.BTN_REPORTS));

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(List.of(row1, row2));
        markup.setResizeKeyboard(true);
        markup.setInputFieldPlaceholder("Ссылка на канал, например t.me/durov…");
        return markup;
    }

    public InlineKeyboardMarkup welcomeInline() {
        return inlineRows(List.of(
                row(button("💡 Как это работает", CallbackData.MENU_HOW_IT_WORKS))
        ));
    }

    public InlineKeyboardMarkup howItWorksInline() {
        return inlineRows(List.of(
                row(button("📢 Подключить свой канал", CallbackData.CHANNEL_CONNECT_PUBLISH)),
                row(button("◀️ В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup backToMainInline() {
        return inlineRows(List.of(row(button("◀️ В меню", CallbackData.MENU_MAIN))));
    }

    public InlineKeyboardMarkup channelConnectedInline() {
        return inlineRows(List.of(
                row(button("🚀 Запустить анализ", CallbackData.REQ_FREE)),
                row(button("◀️ В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup historyListInline(List<Long> requestIds) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Long requestId : requestIds) {
            rows.add(row(button("📂 Открыть отчёт #" + requestId, CallbackData.PREFIX_HIST + "open:" + requestId)));
        }
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup mainMenuInline(int historyCount, boolean billingEnabled) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(button("🔍 Анализ канала", CallbackData.REQ_START)));
        rows.add(row(
                button("📁 Мои отчёты (" + historyCount + ")", CallbackData.PREFIX_HIST + "list"),
                button("📅 Запланированные", CallbackData.SCHEDULE_LIST)
        ));
        rows.add(row(button("💡 Как это работает", CallbackData.MENU_HOW_IT_WORKS)));
        if (billingEnabled) {
            rows.add(row(button("💳 Тарифы", CallbackData.PAY_SELECT)));
        }
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup paymentPackagesInline() {
        return paymentPackagesInline(billingProperties.isEnabled());
    }

    public InlineKeyboardMarkup paymentPackagesInline(boolean billingEnabled) {
        if (!billingEnabled) {
            return inlineRows(List.of(row(button("◀️ В меню", CallbackData.MENU_MAIN))));
        }
        return inlineRows(List.of(row(button("◀️ В меню", CallbackData.MENU_MAIN))));
    }

    public InlineKeyboardMarkup paymentPackagesInline(List<org.example.pulse_ai.persistence.entity.PackageEntity> packages) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (org.example.pulse_ai.persistence.entity.PackageEntity pack : packages) {
            String emoji = switch (pack.getCode()) {
                case "START" -> "🌱";
                case "CONTENT" -> "⭐";
                case "PRO" -> "🚀";
                default -> "💳";
            };
            String label = emoji + " " + pack.getName() + " — " + pack.getStarsAmount() + " ⭐";
            rows.add(row(button(label, CallbackData.PREFIX_PAY + "pack:" + pack.getId())));
        }
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup resultActionsInline(long requestId, boolean teaserMode) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(
                button("📊 Статистика", CallbackData.PREFIX_RESULT + "stats:" + requestId),
                button("💡 3 идеи", CallbackData.PREFIX_RESULT + "ideas:" + requestId)
        ));
        rows.add(row(
                button("📈 Графики", CallbackData.PREFIX_RESULT + "charts:" + requestId),
                backToReportButton(requestId)
        ));
        if (teaserMode && billingProperties.isEnabled()) {
            rows.add(row(button("🚀 Полный разбор + посты", CallbackData.PAY_SELECT)));
        }
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup resultHubInline(long requestId, boolean freeTier) {
        return resultActionsInline(requestId, freeTier);
    }

    public InlineKeyboardMarkup ideasWithDraftsInline(
            long requestId, List<Long> ideaIds, boolean freeTier, int draftsLeft
    ) {
        return ideasFunnelInline(requestId, DeepAnalysisSections.sectionCount(), freeTier, ideaIds, freeTier, draftsLeft);
    }

    public InlineKeyboardMarkup ideasFunnelInline(
            long requestId,
            int sectionTotal,
            boolean teaserMode,
            List<Long> ideaIds,
            boolean freeTier,
            int draftsLeft,
            boolean showBatchPosts
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int ideasIndex = DeepAnalysisSections.ideasFunnelIndex(sectionTotal);
        rows.addAll(buildSectionRows(requestId, ideasIndex, sectionTotal, false));
        appendDraftRows(rows, requestId, ideaIds, freeTier, draftsLeft);
        if (showBatchPosts) {
            rows.add(row(button("📝 7 готовых постов", CallbackData.PREFIX_RESULT + "posts:" + requestId)));
        }
        rows.add(row(backToReportButton(requestId)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup ideasFunnelInline(
            long requestId,
            int sectionTotal,
            boolean teaserMode,
            List<Long> ideaIds,
            boolean freeTier,
            int draftsLeft
    ) {
        return ideasFunnelInline(requestId, sectionTotal, teaserMode, ideaIds, freeTier, draftsLeft, false);
    }

    /**
     * Экран идей с пагинацией: сверху кнопки «Пост N» (N — глобальный номер идеи),
     * снизу стрелки листания, затем возврат к разбору.
     */
    public InlineKeyboardMarkup ideasPageInline(
            long requestId,
            List<Long> pageIdeaIds,
            int globalStart,
            int page,
            int totalPages,
            boolean locked,
            boolean showBatchPosts,
            boolean showIdeasRegen,
            int ideasRegensLeft
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> postRow = new ArrayList<>();
        for (int i = 0; i < pageIdeaIds.size(); i++) {
            int number = globalStart + i + 1;
            String label = locked ? "🔒 Пост " + number : "✍️ Пост " + number;
            String callback = locked
                    ? CallbackData.PREFIX_RESULT + "draftlock:" + requestId
                    : CallbackData.PREFIX_RESULT + "draft:" + requestId + ":" + pageIdeaIds.get(i);
            postRow.add(button(label, callback));
        }
        if (!postRow.isEmpty()) {
            rows.add(postRow);
        }

        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (page > 0) {
                navRow.add(button("◀️", CallbackData.PREFIX_RESULT + "ideapage:" + requestId + ":" + (page - 1)));
            }
            navRow.add(button((page + 1) + "/" + totalPages,
                    CallbackData.PREFIX_RESULT + "ideapage:" + requestId + ":" + page));
            if (page < totalPages - 1) {
                navRow.add(button("▶️", CallbackData.PREFIX_RESULT + "ideapage:" + requestId + ":" + (page + 1)));
            }
            rows.add(navRow);
        }

        if (showBatchPosts) {
            rows.add(row(button("📝 Все посты сразу", CallbackData.PREFIX_RESULT + "posts:" + requestId)));
        }
        if (showIdeasRegen) {
            String regenLabel = ideasRegensLeft > 0
                    ? "🔄 Новые идеи (" + ideasRegensLeft + ")"
                    : "🔒 Новые идеи";
            String regenCallback = ideasRegensLeft > 0
                    ? CallbackData.PREFIX_RESULT + "idearegen:" + requestId
                    : CallbackData.PREFIX_RESULT + "idearegenlock:" + requestId;
            rows.add(row(button(regenLabel, regenCallback)));
        }
        rows.add(row(backToReportButton(requestId)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup generatedPostsInline(long requestId, List<GeneratedPostEntity> posts) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < posts.size(); i++) {
            row.add(button("✍️ Пост " + (i + 1), CallbackData.PREFIX_RESULT + "postview:" + posts.get(i).getId()));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(row(button("💡 К идеям", CallbackData.PREFIX_RESULT + "ideas:" + requestId)));
        rows.add(row(backToReportButton(requestId)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup draftResultInline(
            long requestId,
            long ideaId,
            long postId,
            int sectionTotal,
            boolean teaserMode,
            boolean freeTier,
            int draftsLeft
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(
                button("📤 Опубликовать", CallbackData.PREFIX_PUBLISH + "preview:" + postId),
                button("✏️ Править", CallbackData.PREFIX_PUBLISH + "edit:" + postId)
        ));
        if (imageProperties.isConfigured()) {
            rows.add(row(button("🖼 Подобрать фото", CallbackData.PREFIX_PUBLISH + "photo:" + postId)));
        }
        rows.add(row(
                button("🔄 Другой вариант", CallbackData.PREFIX_RESULT + "draft:" + requestId + ":" + ideaId),
                button("💡 К идеям", CallbackData.PREFIX_RESULT + "ideas:" + requestId)
        ));
        return inlineRows(rows);
    }

    /** Экран сборки опроса: анонимность, свои варианты, публикация / план. */
    public InlineKeyboardMarkup pollBuilderInline(long postId, long requestId, boolean anonymous) {
        String anonLabel = anonymous
                ? "🕶 Анонимный → прямо в канал"
                : "👁 Неанонимный → в комментарии ✓";
        return inlineRows(List.of(
                row(button(anonLabel, CallbackData.PREFIX_POLL + "anon:" + postId)),
                row(
                        button("✏️ Свои варианты", CallbackData.PREFIX_POLL + "custom:" + postId),
                        button("🔄 Другие варианты", CallbackData.PREFIX_POLL + "regen:" + postId)
                ),
                row(
                        button("✅ Опубликовать опрос", CallbackData.PREFIX_PUBLISH + "confirm:" + postId),
                        button("📅 Запланировать", CallbackData.PREFIX_SCHEDULE + "open:" + postId)
                ),
                row(button("💡 К идеям", CallbackData.PREFIX_RESULT + "ideas:" + requestId))
        ));
    }

    public InlineKeyboardMarkup publishPreviewInline(long postId) {
        return inlineRows(List.of(
                row(
                        button("✅ Опубликовать сейчас", CallbackData.PREFIX_PUBLISH + "confirm:" + postId),
                        button("✏️ Редактировать", CallbackData.PREFIX_PUBLISH + "edit:" + postId)
                ),
                row(button("📅 Запланировать", CallbackData.PREFIX_SCHEDULE + "open:" + postId)),
                row(button("◀️ Назад", CallbackData.PREFIX_PUBLISH + "cancel:" + postId))
        ));
    }

    public InlineKeyboardMarkup agentInline(boolean enabled, boolean subscribed, long leadCount, boolean showScoutAdmin) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (subscribed) {
            rows.add(row(button(enabled ? "🟢 Агент включён — выключить" : "⚪️ Включить агента",
                    CallbackData.AGENT_TOGGLE)));
        } else {
            rows.add(row(button("💳 Подключить (тарифы)", CallbackData.PAY_SELECT)));
        }
        rows.add(row(button("🔥 Лиды" + (leadCount > 0 ? " (" + leadCount + ")" : ""), CallbackData.AGENT_LEADS)));
        rows.add(row(button("🧠 Профиль компании (рамки агента)", CallbackData.AGENT_FAQ)));
        rows.add(row(button("📘 Книга возражений", CallbackData.AGENT_OBJECTIONS)));
        rows.add(row(button("📌 Выводы (обучается)", CallbackData.AGENT_LEARNINGS)));
        rows.add(row(button("📡 Ad Radar (реклама)", CallbackData.AGENT_RADAR)));
        rows.add(row(button("📤 Рассылки в ЛС", CallbackData.AGENT_OUTREACH)));
        if (showScoutAdmin) {
            rows.add(row(button("🛰 Scout status", CallbackData.AGENT_SCOUT_STATUS)));
        }
        rows.add(row(button("❓ Как подключить комментарии", CallbackData.AGENT_HELP)));
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup agentBackInline() {
        return inlineRows(List.of(
                row(button("◀️ К агенту", CallbackData.AGENT_OPEN)),
                row(button("🏠 В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup adRadarMenuInline() {
        return inlineRows(List.of(
                row(
                        button("➕ Чат на мониторинг", CallbackData.AGENT_RADAR_ADD_WATCH),
                        button("🔎 Проверить канал", CallbackData.AGENT_RADAR_ADD_PLACE)
                ),
                row(
                        button("📋 Мои чаты", CallbackData.AGENT_RADAR_WATCHES),
                        button("🔍 Площадки", CallbackData.AGENT_RADAR_PLACES)
                ),
                row(button("📣 Сигналы", CallbackData.AGENT_RADAR_HITS)),
                row(button("◀️ К Jarvis", CallbackData.AGENT_OPEN)),
                row(button("🏠 В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup adRadarPlacementsInline(List<AdPlacementEntity> places) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AdPlacementEntity p : places.stream().limit(5).toList()) {
            rows.add(row(button("🔄 @" + p.getTargetUsername(),
                    CallbackData.AGENT_RADAR_RECHECK + p.getId())));
        }
        rows.add(row(button("◀️ Назад", CallbackData.AGENT_RADAR)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup outreachMenuInline(List<OutreachCampaignEntity> campaigns) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(button("➕ Новая кампания", CallbackData.AGENT_OUTREACH_NEW)));
        for (OutreachCampaignEntity c : campaigns.stream().limit(3).toList()) {
            rows.add(row(button("#" + c.getId() + " · " + c.getName(),
                    CallbackData.AGENT_OUTREACH_VIEW + c.getId())));
        }
        rows.add(row(button("◀️ К Jarvis", CallbackData.AGENT_OPEN)));
        rows.add(row(button("🏠 В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup outreachScenarioInline() {
        return inlineRows(List.of(
                row(
                        button("📨 Invite в канал", CallbackData.AGENT_OUTREACH_SCENARIO + "INVITE"),
                        button("📝 Custdev", CallbackData.AGENT_OUTREACH_SCENARIO + "CUSTDEV")
                ),
                row(button("💼 Оффер", CallbackData.AGENT_OUTREACH_SCENARIO + "OFFER")),
                row(button("◀️ Назад", CallbackData.AGENT_OUTREACH))
        ));
    }

    public InlineKeyboardMarkup outreachCampaignInline(OutreachCampaignEntity campaign) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String st = campaign.getStatus();
        if ("DRAFT".equals(st) || "PAUSED".equals(st)) {
            rows.add(row(button("▶️ Запустить", CallbackData.AGENT_OUTREACH_START + campaign.getId())));
        }
        if ("RUNNING".equals(st)) {
            rows.add(row(button("⏸ Пауза", CallbackData.AGENT_OUTREACH_PAUSE + campaign.getId())));
        }
        rows.add(row(button("➕ Добавить @username", CallbackData.AGENT_OUTREACH_IMPORT + campaign.getId())));
        if (campaign.getSourceRef() != null && !campaign.getSourceRef().isBlank()) {
            rows.add(row(button("🔍 Парсить группу", CallbackData.AGENT_OUTREACH_PARSE + campaign.getId())));
        }
        rows.add(row(button("◀️ К рассылкам", CallbackData.AGENT_OUTREACH)));
        return inlineRows(rows);
    }

    /** Слот времени публикации: подпись, момент (epoch seconds) и флаг «лучший охват» (🔥). */
    public record TimeSlot(String label, long epochSecond, boolean hot) {
    }

    public InlineKeyboardMarkup scheduleOptionsInline(long postId, String bestTimeLabel, List<TimeSlot> slots) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (bestTimeLabel != null && !bestTimeLabel.isBlank()) {
            rows.add(row(button(bestTimeLabel, CallbackData.PREFIX_SCHEDULE + "best:" + postId)));
        }
        rows.add(row(button("⏰ Через 1 час", CallbackData.PREFIX_SCHEDULE + "1h:" + postId)));

        if (slots != null && !slots.isEmpty()) {
            List<InlineKeyboardButton> pair = new ArrayList<>();
            for (TimeSlot slot : slots) {
                String label = (slot.hot() ? "🔥 " : "") + slot.label();
                pair.add(button(label, CallbackData.PREFIX_SCHEDULE + "at:" + slot.epochSecond() + ":" + postId));
                if (pair.size() == 2) {
                    rows.add(row(pair.toArray(new InlineKeyboardButton[0])));
                    pair = new ArrayList<>();
                }
            }
            if (!pair.isEmpty()) {
                rows.add(row(pair.toArray(new InlineKeyboardButton[0])));
            }
        }

        rows.add(row(button("✏️ Своё время", CallbackData.PREFIX_SCHEDULE + "custom:" + postId)));
        rows.add(row(button("◀️ Назад", CallbackData.PREFIX_PUBLISH + "preview:" + postId)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup scheduledListInline(List<org.example.pulse_ai.persistence.entity.ScheduledPostEntity> items) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (org.example.pulse_ai.persistence.entity.ScheduledPostEntity item : items) {
            rows.add(row(button("❌ Отменить #" + item.getId(),
                    CallbackData.PREFIX_SCHEDULE + "cancel:" + item.getId())));
        }
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup scheduledConfirmInline(long scheduledId, long requestId) {
        return inlineRows(List.of(
                row(button("✍️ Следующий пост", CallbackData.PREFIX_RESULT + "ideas:" + requestId)),
                row(button("📅 Мои запланированные", CallbackData.SCHEDULE_LIST)),
                row(button("❌ Отменить эту публикацию", CallbackData.PREFIX_SCHEDULE + "cancel:" + scheduledId)),
                row(button("◀️ В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup scheduledConfirmInline(long scheduledId) {
        return inlineRows(List.of(
                row(button("📅 Мои запланированные", CallbackData.SCHEDULE_LIST)),
                row(button("❌ Отменить эту публикацию", CallbackData.PREFIX_SCHEDULE + "cancel:" + scheduledId)),
                row(button("◀️ В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup publishEditInline(long postId) {
        return inlineRows(List.of(
                row(button("◀️ Отмена", CallbackData.PREFIX_PUBLISH + "cancel:" + postId))
        ));
    }

    public InlineKeyboardMarkup photoPreviewInline(long postId) {
        return inlineRows(List.of(
                row(
                        button("✅ Опубликовать с фото", CallbackData.PREFIX_PUBLISH + "confirm:" + postId),
                        button("◀️ К посту", CallbackData.PREFIX_PUBLISH + "preview:" + postId)
                ),
                row(
                        button("🔄 Другое фото", CallbackData.PREFIX_PUBLISH + "rephoto:" + postId),
                        button("❌ Без фото", CallbackData.PREFIX_PUBLISH + "nophoto:" + postId)
                )
        ));
    }

    public InlineKeyboardMarkup publishFailedInline(long postId, long requestId) {
        return inlineRows(List.of(
                row(
                        button("🔄 Повторить", CallbackData.PREFIX_PUBLISH + "retry:" + postId),
                        button("✏️ Редактировать", CallbackData.PREFIX_PUBLISH + "edit:" + postId)
                ),
                row(backToReportButton(requestId))
        ));
    }

    public InlineKeyboardMarkup publishSuccessInline(long requestId) {
        return inlineRows(List.of(
                row(button("✍️ Следующий пост", CallbackData.PREFIX_RESULT + "ideas:" + requestId)),
                row(
                        button("📅 Запланированные", CallbackData.SCHEDULE_LIST),
                        backToReportButton(requestId)
                ),
                row(button("◀️ В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup productRubricsInline() {
        return productMenuInline();
    }

    public InlineKeyboardMarkup productMenuInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(
                button("🔄 Учиться с каналов", CallbackData.PRODUCT_SYNC),
                button("📊 Отчёт", CallbackData.PRODUCT_REPORT)
        ));
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (org.example.pulse_ai.domain.product.ProductPostRubric rubric
                : org.example.pulse_ai.domain.product.ProductPostRubric.values()) {
            row.add(button(
                    rubric.label(),
                    CallbackData.PREFIX_PRODUCT + "gen:" + rubric.name()));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup productPreviewInline(long postId) {
        return inlineRows(List.of(
                row(
                        button("✅ Опубликовать", CallbackData.PREFIX_PRODUCT + "confirm:" + postId),
                        button("✏️ Править", CallbackData.PREFIX_PRODUCT + "edit:" + postId)
                ),
                row(button("◀️ Назад", CallbackData.PRODUCT_MENU))
        ));
    }

    public InlineKeyboardMarkup productEditInline(long postId) {
        return inlineRows(List.of(
                row(button("◀️ Отмена", CallbackData.PREFIX_PRODUCT + "cancel:" + postId))
        ));
    }

    public InlineKeyboardMarkup analysisSectionsInline(long requestId, int current, int total, boolean teaserMode) {
        return inlineRows(buildSectionRows(requestId, current, total, teaserMode));
    }

    private List<List<InlineKeyboardButton>> buildSectionRows(
            long requestId, int current, int total, boolean teaserMode
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String label = DeepAnalysisSections.shortLabel(i);
            if (current >= 0 && i == current) {
                label = "• " + label;
            }
            String callback = CallbackData.PREFIX_RESULT + "sec:" + requestId + ":" + i;
            row.add(button(label, callback));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    public InlineKeyboardMarkup featureHubInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(
                button("📬 Дайджест недели", CallbackData.FEAT_DIGEST),
                button("💰 Продающий пост", CallbackData.FEAT_SELLING)
        ));
        if (billingProperties.isEnabled()) {
            rows.add(row(button("💳 Пакеты", CallbackData.PAY_SELECT)));
        }
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup perkPickerInline(
            long paymentId, String packageCode, int remaining, int total
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<org.example.pulse_ai.domain.entitlement.PerkType> perks =
                org.example.pulse_ai.domain.entitlement.PerkType.pickableForPackage(packageCode);
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (org.example.pulse_ai.domain.entitlement.PerkType perk : perks) {
            row.add(button(perk.label(), CallbackData.PREFIX_PERK + "pick:" + paymentId + ":" + perk.code()));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(row(button("◀️ Позже", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    private InlineKeyboardButton backToReportButton(long requestId) {
        return button("◀️ К разбору", CallbackData.PREFIX_RESULT + "hub:" + requestId);
    }

    private void appendDraftRows(
            List<List<InlineKeyboardButton>> rows,
            long requestId,
            List<Long> ideaIds,
            boolean freeTier,
            int draftsLeft
    ) {
        List<InlineKeyboardButton> draftRow = new ArrayList<>();
        for (int i = 0; i < ideaIds.size(); i++) {
            boolean locked = freeTier && draftsLeft <= 0;
            String label = locked ? "🔒 Пост " + (i + 1) : "✍️ Пост " + (i + 1);
            String callback = locked
                    ? CallbackData.PREFIX_RESULT + "draftlock:" + requestId
                    : CallbackData.PREFIX_RESULT + "draft:" + requestId + ":" + ideaIds.get(i);
            draftRow.add(button(label, callback));
            if (draftRow.size() == 2) {
                rows.add(draftRow);
                draftRow = new ArrayList<>();
            }
        }
        if (!draftRow.isEmpty()) {
            rows.add(draftRow);
        }
    }

    public InlineKeyboardMarkup resultMenuInline(long requestId, boolean freeTier) {
        return resultHubInline(requestId, freeTier);
    }

    public InlineKeyboardMarkup inlineConfirm(String confirmCallback, String cancelCallback) {
        return inlineRows(List.of(
                row(
                        button("✅ Да", confirmCallback),
                        button("◀️ Отмена", cancelCallback)
                )
        ));
    }

    private static List<InlineKeyboardButton> row(InlineKeyboardButton... buttons) {
        return List.of(buttons);
    }

    private InlineKeyboardMarkup inlineRows(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton inlineButton = new InlineKeyboardButton();
        inlineButton.setText(text);
        inlineButton.setCallbackData(callbackData);
        return inlineButton;
    }

    private static InlineKeyboardButton urlButton(String text, String url) {
        InlineKeyboardButton inlineButton = new InlineKeyboardButton();
        inlineButton.setText(text);
        inlineButton.setUrl(url);
        return inlineButton;
    }

    /** Кнопки под уведомлением о горячем лиде: ответ (апрув), свой ответ, CRM-статусы, открыть коммент. */
    public InlineKeyboardMarkup leadNotificationInline(long leadId, boolean hasReply, String commentLink) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (hasReply) {
            rows.add(row(
                    button("✅ Отправить ответ", CallbackData.AGENT_REPLY + leadId),
                    button("✏️ Свой ответ", CallbackData.AGENT_REPLY_EDIT + leadId)
            ));
        } else {
            rows.add(row(button("✏️ Ответить", CallbackData.AGENT_REPLY_EDIT + leadId)));
        }
        rows.add(row(
                button("📞 В работе", CallbackData.AGENT_STATUS + "IN_PROGRESS:" + leadId),
                button("✅ Продажа", CallbackData.AGENT_STATUS + "WON:" + leadId)
        ));
        rows.add(row(button("❌ Слив", CallbackData.AGENT_STATUS + "LOST:" + leadId)));
        if (commentLink != null && !commentLink.isBlank()) {
            rows.add(row(urlButton("🔗 Открыть комментарий", commentLink)));
        }
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup faqInline(boolean hasFaq) {
        return inlineRows(List.of(
                row(button(hasFaq ? "✏️ Изменить профиль" : "➕ Заполнить профиль",
                        CallbackData.AGENT_FAQ_SET)),
                row(button("◀️ К агенту", CallbackData.AGENT_OPEN)),
                row(button("🏠 В меню", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup objectionsInline(boolean hasBook) {
        return inlineRows(List.of(
                row(button(hasBook ? "✏️ Изменить книгу" : "➕ Заполнить книгу возражений",
                        CallbackData.AGENT_OBJECTIONS_SET)),
                row(button("◀️ К агенту", CallbackData.AGENT_OPEN)),
                row(button("🏠 В меню", CallbackData.MENU_MAIN))
        ));
    }
}
