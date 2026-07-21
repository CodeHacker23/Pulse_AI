package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.request.RequestStatus;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MenuHandler {

    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;
    private final AnalysisRequestRepository requestRepository;
    private final ResultCallbackHandler resultCallbackHandler;

    public void showWelcome(long chatId, UserEntity user) {
        sessionService.setState(chatId, BotState.MAIN_MENU);
        messageSender.sendText(chatId, BotMessages.WELCOME, keyboards.startMenuKeyboard());
    }

    public void showMainMenu(long chatId, UserEntity user) {
        UserSession session = sessionService.getOrCreate(chatId);
        Long keepRequest = session.getLastRequestId() != null ? session.getLastRequestId() : session.getRequestId();
        sessionService.resetToMainMenu(chatId);
        if (keepRequest != null) {
            sessionService.getOrCreate(chatId).setLastRequestId(keepRequest);
        }
        String channelTitle = userService.findActiveChannel(user)
                .map(ChannelEntity::getTitle)
                .orElse(null);
        messageSender.sendText(
                chatId,
                BotMessages.mainMenu(channelTitle, user.getBalance(), billingProperties.isEnabled()),
                keyboards.mainMenuKeyboard(billingProperties.isEnabled())
        );
    }

    /** Идеи и черновики по последнему разбору — без повторного анализа. */
    public void showContentHub(long chatId, UserEntity user) {
        Optional<Long> requestId = resolveLastRequestId(chatId, user);
        if (requestId.isEmpty()) {
            messageSender.sendTextWithInlineSafe(chatId,
                    "✍️ <b>Контент</b>\n\nПока нет готового разбора. "
                            + "Сначала проанализируйте канал — потом здесь сразу откроются идеи и черновики.",
                    analyticsEmptyInline());
            return;
        }
        Long id = requestId.get();
        sessionService.getOrCreate(chatId).setLastRequestId(id);
        sessionService.getOrCreate(chatId).setRequestId(id);
        resultCallbackHandler.openIdeas(chatId, id);
    }

    /** Отчёты / повторный анализ / список истории. */
    public void showAnalyticsHub(long chatId, UserEntity user) {
        Optional<ChannelEntity> channel = userService.findActiveChannel(user);
        if (channel.isEmpty()) {
            messageSender.sendText(chatId,
                    "📊 <b>Аналитика</b>\n\nСначала пришлите ссылку на канал — например <code>t.me/durov</code>.");
            return;
        }
        Optional<Long> requestId = resolveLastRequestId(chatId, user);
        String title = TgHtml.esc(channel.get().getTitle());
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Аналитика</b>\n");
        sb.append("Канал: «").append(title).append("»\n\n");
        if (requestId.isPresent()) {
            sb.append("Есть готовый отчёт #").append(requestId.get())
                    .append(" — откройте его или запустите новый разбор.");
        } else {
            sb.append("Готового отчёта пока нет. Запустите анализ канала.");
        }
        messageSender.sendTextWithInlineSafe(chatId, sb.toString(), analyticsHubInline(requestId.orElse(null)));
    }

    public void showSettings(long chatId, UserEntity user) {
        Optional<ChannelEntity> channel = userService.findActiveChannel(user);
        String text;
        if (channel.isPresent()) {
            ChannelEntity c = channel.get();
            text = "⚙️ <b>Канал</b>\n\n"
                    + "Сейчас: «" + TgHtml.esc(c.getTitle()) + "»\n\n"
                    + "• Чтобы <b>опубликовать</b> посты — бот должен быть админом с правом публикации.\n"
                    + "• Сменить канал — пришлите новую ссылку или перешлите пост.";
        } else {
            text = "⚙️ <b>Канал</b>\n\nКанал ещё не подключён. Пришлите ссылку t.me/… или перешлите пост из своего канала.";
        }
        messageSender.sendTextWithInlineSafe(chatId, text, settingsInline());
    }

    public void showHowItWorks(long chatId) {
        messageSender.sendTextWithInline(chatId, BotMessages.HOW_IT_WORKS, keyboards.howItWorksInline());
    }

    public void showWhatInRequest(long chatId) {
        messageSender.sendTextWithInline(
                chatId,
                """
                        📦 <b>Что такое 1 запрос</b>

                        <b>1 запрос</b> = один полный разбор канала:
                        • анализ стиля, топов, времени публикации
                        • 8–12 идей контента
                        • до 7 черновиков постов (нажали «Пост N»)
                        • 1–2 обновления пула идей — <b>без</b> нового запроса

                        <b>Не списывается отдельно:</b>
                        • публикация и планирование постов
                        • подбор и смена фото
                        • повторная генерация текста по той же идее

                        <b>Новый запрос</b> нужен, когда хотите свежий разбор канала
                        (новая статистика, новый пул идей).

                        Пакеты:
                        • Старт — 10 запросов — 990 ₽
                        • Контент — 18 запросов — 1 600 ₽ ⭐
                        • Про — 30 запросов — 2 300 ₽""",
                keyboards.paymentPackagesInline()
        );
    }

    public void showHelp(long chatId) {
        messageSender.sendText(chatId, BotMessages.HELP, keyboards.mainMenuKeyboard());
    }

    public void showBalance(long chatId, UserEntity user) {
        messageSender.sendTextWithInline(
                chatId,
                BotMessages.balance(user.getBalance(), billingProperties.isEnabled()),
                keyboards.paymentPackagesInline(billingProperties.isEnabled())
        );
    }

    private Optional<Long> resolveLastRequestId(long chatId, UserEntity user) {
        UserSession session = sessionService.getOrCreate(chatId);
        if (session.getLastRequestId() != null) {
            return Optional.of(session.getLastRequestId());
        }
        if (session.getRequestId() != null) {
            return Optional.of(session.getRequestId());
        }
        return requestRepository.findTop1ByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), RequestStatus.COMPLETED)
                .stream()
                .findFirst()
                .map(AnalysisRequestEntity::getId);
    }

    private InlineKeyboardMarkup analyticsHubInline(Long requestId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (requestId != null) {
            rows.add(List.of(btn("📂 Открыть последний отчёт", CallbackData.PREFIX_HIST + "open:" + requestId)));
            rows.add(List.of(btn("✍️ К идеям постов", CallbackData.PREFIX_RESULT + "ideas:" + requestId)));
        }
        rows.add(List.of(btn("📁 Все отчёты", CallbackData.PREFIX_HIST + "list")));
        rows.add(List.of(btn("🔍 Новый анализ", CallbackData.REQ_FREE)));
        rows.add(List.of(btn("◀️ В меню", CallbackData.MENU_MAIN)));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup analyticsEmptyInline() {
        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(btn("🔍 Запустить анализ", CallbackData.REQ_FREE)),
                List.of(btn("◀️ В меню", CallbackData.MENU_MAIN))
        );
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup settingsInline() {
        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(btn("📢 Подключить для публикации", CallbackData.CHANNEL_CONNECT_PUBLISH)),
                List.of(btn("💡 Как это работает", CallbackData.MENU_HOW_IT_WORKS)),
                List.of(btn("◀️ В меню", CallbackData.MENU_MAIN))
        );
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private static InlineKeyboardButton btn(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text);
        b.setCallbackData(data);
        return b;
    }
}
