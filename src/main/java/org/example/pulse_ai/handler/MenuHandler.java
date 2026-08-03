package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.request.RequestStatus;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.domain.user.UserTimezoneService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AnalysisRequestEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.persistence.repository.ChannelRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.stats.external.TgstatAccessService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MenuHandler {

    private static final int STYLE_PROMPT_MAX = 2000;

    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;
    private final AnalysisRequestRepository requestRepository;
    private final ChannelRepository channelRepository;
    private final ManagerHandler managerHandler;
    private final UserTimezoneService timezoneService;
    private final TgstatAccessService tgstatAccessService;

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

    /** Хаб контента: идеи, аналитика, расписание, свой промпт стиля. */
    public void showContentHub(long chatId, UserEntity user) {
        Optional<Long> requestId = resolveLastRequestId(chatId, user);
        requestId.ifPresent(id -> {
            sessionService.getOrCreate(chatId).setLastRequestId(id);
            sessionService.getOrCreate(chatId).setRequestId(id);
        });
        boolean hasChannel = userService.findActiveChannel(user).isPresent();
        StringBuilder sb = new StringBuilder("✍️ <b>Контент</b>\n\n");
        if (!hasChannel) {
            sb.append("Пришлите ссылку на канал — потом здесь появятся идеи, черновики и ваш стиль.");
        } else if (requestId.isEmpty()) {
            sb.append("Канал подключён. Запустите анализ — появятся идеи и черновики постов.\n")
                    .append("Можно сразу задать <b>промпт стиля</b>: он важнее примеров с канала.");
        } else {
            sb.append("Открывайте идеи и черновики, смотрите аналитику, правьте расписание.\n")
                    .append("Промпт стиля — если задан — идёт <b>первым</b> при генерации постов.");
        }
        messageSender.sendTextWithInlineSafe(chatId, sb.toString(), keyboards.contentHubInline(requestId.orElse(null)));
    }

    public void showMore(long chatId, UserEntity user) {
        String tz = UserTimezoneService.displayName(timezoneService.zoneOf(user.getId()));
        String tzShort = UserTimezoneService.shortLabel(timezoneService.zoneOf(user.getId()));
        messageSender.sendTextWithInlineSafe(
                chatId,
                "⚙️ <b>Кабинет</b>\n\n"
                        + "Канал, тарифы, помощь и часовой пояс.\n"
                        + "Сейчас пояс: <b>" + TgHtml.esc(tz) + "</b> (" + TgHtml.esc(tzShort) + ").",
                keyboards.moreMenuInline(billingProperties.isEnabled())
        );
    }

    public void showTimezonePicker(long chatId, UserEntity user) {
        ZoneId zone = timezoneService.zoneOf(user.getId());
        messageSender.sendTextWithInlineSafe(
                chatId,
                "🕐 <b>Часовой пояс</b>\n\n"
                        + "Сейчас: <b>" + TgHtml.esc(UserTimezoneService.displayName(zone)) + "</b>\n"
                        + "Слоты публикации и подписи будут в этом поясе. Прошедшее время скрывается.",
                keyboards.timezonePickerInline(zone.getId())
        );
    }

    public void setTimezone(long chatId, UserEntity user, String zoneId) {
        timezoneService.setTimezone(user.getId(), zoneId);
        ZoneId zone = timezoneService.zoneOf(user.getId());
        messageSender.sendTextSafe(chatId,
                "✅ Пояс: <b>" + TgHtml.esc(UserTimezoneService.displayName(zone)) + "</b> ("
                        + TgHtml.esc(UserTimezoneService.shortLabel(zone)) + ")");
        showMore(chatId, user);
    }

    /** Глубокая аналитика (CONTENT+ / TGStat) из раздела Рост. */
    public void showAnalyticsPlus(long chatId, UserEntity user) {
        boolean deep = tgstatAccessService.forPlacementSearch(user.getId())
                || (!billingProperties.isEnabled() && tgstatAccessService.tokenConfigured());
        if (!deep && billingProperties.isEnabled()) {
            messageSender.sendTextWithInlineSafe(chatId,
                    "📊 <b>Аналитика+</b>\n\n"
                            + "Глубокие метрики (ERR, охват, ниша, TGStat) — в тарифах <b>CONTENT</b> и <b>PRO</b>.\n\n"
                            + "Базовый разбор канала доступен в «✍️ Контент» → аналитика / новый анализ.",
                    keyboards.paymentPackagesInline(billingProperties.isEnabled()));
            return;
        }
        StringBuilder sb = new StringBuilder("📊 <b>Аналитика+</b>\n\n");
        sb.append("Глубокий слой: внешние метрики, ERR, ниша, сравнение площадок.\n");
        sb.append("Базовый срез после разбора — в «✍️ Контент».\n\n");
        Optional<Long> requestId = resolveLastRequestId(chatId, user);
        if (requestId.isPresent()) {
            sb.append("Откройте последний отчёт или запустите новый разбор с полным слоем.");
        } else {
            sb.append("Запустите анализ канала — подтянем глубокие метрики.");
        }
        messageSender.sendTextWithInlineSafe(chatId, sb.toString(), analyticsPlusInline(requestId.orElse(null)));
    }

    private InlineKeyboardMarkup analyticsPlusInline(Long requestId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (requestId != null) {
            rows.add(List.of(btn("📂 Открыть отчёт", CallbackData.PREFIX_HIST + "open:" + requestId)));
            rows.add(List.of(btn("📈 Статистика отчёта", CallbackData.PREFIX_RESULT + "stats:" + requestId)));
        }
        rows.add(List.of(btn("🔍 Новый анализ", CallbackData.REQ_FREE)));
        rows.add(List.of(btn("📡 Площадки для рекламы", CallbackData.AGENT_RADAR)));
        rows.add(List.of(btn("◀️ К росту", CallbackData.MENU_GROWTH)));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    public void showGrowth(long chatId, UserEntity user) {
        managerHandler.openGrowth(chatId, user);
    }

    public void showStylePrompt(long chatId, UserEntity user) {
        Optional<ChannelEntity> channel = userService.findActiveChannel(user);
        if (channel.isEmpty()) {
            messageSender.sendText(chatId,
                    "🎛 <b>Промпт стиля</b>\n\nСначала пришлите ссылку на канал.");
            return;
        }
        String prompt = channel.get().getContentStylePrompt();
        boolean has = prompt != null && !prompt.isBlank();
        String body = has
                ? "🎛 <b>Мой промпт стиля</b>\n\nСейчас:\n<code>"
                + TgHtml.esc(truncate(prompt.trim(), 900))
                + "</code>\n\nОн идёт <b>первым</b> при генерации — важнее примеров с канала."
                : "🎛 <b>Мой промпт стиля</b>\n\nПока пусто. Напишите, как писать посты: тон, длина, табу, CTA.\n"
                + "Этот текст будет приоритетнее разбора стиля канала.";
        messageSender.sendTextWithInlineSafe(chatId, body, keyboards.stylePromptInline(has));
    }

    public void promptStyleInput(long chatId, UserEntity user) {
        if (userService.findActiveChannel(user).isEmpty()) {
            messageSender.sendText(chatId, "Сначала пришлите ссылку на канал.");
            return;
        }
        sessionService.setState(chatId, BotState.STYLE_PROMPT_INPUT);
        messageSender.sendText(chatId,
                "Пришлите промпт стиля одним сообщением (до " + STYLE_PROMPT_MAX + " символов).\n"
                        + "Или /cancel — отмена.");
    }

    public void clearStylePrompt(long chatId, UserEntity user) {
        Optional<ChannelEntity> channel = userService.findActiveChannel(user);
        if (channel.isEmpty()) {
            messageSender.sendText(chatId, "Сначала пришлите ссылку на канал.");
            return;
        }
        ChannelEntity c = channel.get();
        c.setContentStylePrompt(null);
        channelRepository.save(c);
        messageSender.sendText(chatId, "Промпт стиля очищен. Генерация снова опирается на примеры канала.");
        showStylePrompt(chatId, user);
    }

    public void handleStylePromptInput(long chatId, UserEntity user, String text) {
        Optional<ChannelEntity> channel = userService.findActiveChannel(user);
        if (channel.isEmpty()) {
            sessionService.setState(chatId, BotState.MAIN_MENU);
            messageSender.sendText(chatId, "Сначала пришлите ссылку на канал.");
            return;
        }
        String trimmed = text.trim();
        if (trimmed.length() > STYLE_PROMPT_MAX) {
            messageSender.sendText(chatId, "Слишком длинно — максимум " + STYLE_PROMPT_MAX + " символов. Сократите.");
            return;
        }
        ChannelEntity c = channel.get();
        c.setContentStylePrompt(trimmed);
        channelRepository.save(c);
        sessionService.setState(chatId, BotState.MAIN_MENU);
        messageSender.sendText(chatId, "✅ Промпт сохранён. При генерации постов он будет первым.");
        showStylePrompt(chatId, user);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
