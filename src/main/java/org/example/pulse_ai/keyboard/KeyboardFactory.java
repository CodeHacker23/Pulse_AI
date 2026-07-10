package org.example.pulse_ai.keyboard;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.analysis.DeepAnalysisSections;
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

    public ReplyKeyboardMarkup mainMenuKeyboard() {
        return mainMenuKeyboard(billingProperties.isEnabled());
    }
    public ReplyKeyboardMarkup mainMenuKeyboard(boolean billingEnabled) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(MenuText.BTN_ANALYZE));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(MenuText.BTN_REPORTS));
        row2.add(new KeyboardButton(MenuText.BTN_HOW));

        List<KeyboardRow> rows = new ArrayList<>(List.of(row1, row2));
        if (billingEnabled) {
            KeyboardRow row3 = new KeyboardRow();
            row3.add(new KeyboardButton(MenuText.BTN_BUY));
            rows.add(row3);
        }

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(rows);
        markup.setResizeKeyboard(true);
        markup.setInputFieldPlaceholder("Отправьте ссылку на канал…");
        return markup;
    }

    public InlineKeyboardMarkup mainMenuInline(int historyCount) {
        return mainMenuInline(historyCount, billingProperties.isEnabled());
    }

    public InlineKeyboardMarkup welcomeInline() {
        return inlineRows(List.of(
                row(button("🔍 Анализировать канал", CallbackData.CHANNEL_CONNECT)),
                row(button("💡 Как это работает", CallbackData.MENU_HOW_IT_WORKS))
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

    public InlineKeyboardMarkup mainMenuInline(int historyCount, boolean billingEnabled) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(button("🔍 Анализ канала", CallbackData.REQ_START)));
        rows.add(row(
                button("📁 Мои отчёты (" + historyCount + ")", CallbackData.PREFIX_HIST + "list"),
                button("💡 Как это работает", CallbackData.MENU_HOW_IT_WORKS)
        ));
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
        return inlineRows(List.of(
                row(button("🌱 Старт — 990 ₽", CallbackData.PREFIX_PAY + "pack:10")),
                row(button("⭐ Контент — 1 600 ₽", CallbackData.PREFIX_PAY + "pack:18")),
                row(button("🚀 Про — 2 300 ₽", CallbackData.PREFIX_PAY + "pack:30")),
                row(button("◀️ Назад", CallbackData.MENU_MAIN))
        ));
    }

    public InlineKeyboardMarkup resultActionsInline(long requestId, boolean teaserMode) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(
                button("📊 Статистика", CallbackData.PREFIX_RESULT + "stats:" + requestId),
                button("💡 3 идеи", CallbackData.PREFIX_RESULT + "ideas:" + requestId)
        ));
        rows.add(row(
                button("📈 Графики", CallbackData.PREFIX_RESULT + "charts:" + requestId),
                button("◀️ В меню", CallbackData.MENU_MAIN)
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
            int draftsLeft
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        appendDraftRows(rows, requestId, ideaIds, freeTier, draftsLeft);
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup draftResultInline(
            long requestId,
            long ideaId,
            int sectionTotal,
            boolean teaserMode,
            boolean freeTier,
            int draftsLeft
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(
                button("🔄 Другой вариант", CallbackData.PREFIX_RESULT + "draft:" + requestId + ":" + ideaId),
                button("💡 К идеям", CallbackData.PREFIX_RESULT + "ideas:" + requestId)
        ));
        rows.add(row(button("◀️ В меню", CallbackData.MENU_MAIN)));
        return inlineRows(rows);
    }

    public InlineKeyboardMarkup analysisSectionsInline(long requestId, int current, int total, boolean teaserMode) {
        return inlineRows(buildSectionRows(requestId, current, total, teaserMode));
    }

    private List<List<InlineKeyboardButton>> buildSectionRows(
            long requestId, int current, int total, boolean teaserMode
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        int ideasIndex = DeepAnalysisSections.ideasFunnelIndex(total);
        for (int i = 0; i < total; i++) {
            String label = DeepAnalysisSections.shortLabel(i);
            if (i == current) {
                label = "• " + label;
            }
            String callback;
            if (teaserMode && i > 0 && i < ideasIndex) {
                callback = CallbackData.PREFIX_RESULT + "lock:" + requestId;
                label = "🔒 " + DeepAnalysisSections.shortLabel(i).substring(2);
            } else {
                callback = CallbackData.PREFIX_RESULT + "sec:" + requestId + ":" + i;
            }
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
}
