package org.example.pulse_ai.text;

public final class BotMessages {

    public static final String WELCOME = """
            👋 <b>Pulse AI</b> — разбор Telegram-каналов и готовые посты под ваш стиль.

            Пришлите ссылку на любой открытый канал — я соберу метрики, разберу контент и предложу идеи.

            Не знаете с чего начать? Нажмите <b>«Как это работает»</b> 👇""";

    public static final String HOW_IT_WORKS = """
            💡 <b>Как это работает — 3 шага</b>

            <b>1️⃣ Пришлите ссылку на канал</b>
            Например: <code>https://t.me/durov</code> или <code>@durov</code>

            <b>2️⃣ Я анализирую данные</b>
            Посты, просмотры и метрики — беру на себя

            <b>3️⃣ Вы получаете отчёт</b>
            Графики, цифры, разбор контента и конкретные идеи — за 1–3 минуты

            ✅ Работает с любым открытым каналом.""";

    public static final String CONNECT_PUBLISH_CHANNEL = """
            📢 <b>Свой канал для публикации</b>

            Анализ по ссылке можно делать на любой открытый канал.
            <b>Публиковать</b> бот умеет только туда, где он администратор.

            <b>Как подключить:</b>
            1. Откройте свой канал → Администраторы → Добавить администратора
            2. Найдите этого бота и дайте право <b>«Публикация сообщений»</b>
               (по желанию — «Редактирование» и доступ к статистике)
            3. Перешлите сюда <b>любой пост</b> из этого канала

            После этого «Опубликовать» / «Запланировать» заработают.""";

    public static final String CONNECT_CHANNEL = """
            🔍 <b>Пришлите ссылку на канал</b>

            Просто отправьте ссылку или @username открытого канала:
            • <code>https://t.me/durov</code>
            • <code>@durov</code>

            Дальше я всё сделаю сам:
            📥 соберу посты и просмотры
            📊 подтяну метрики
            🧠 разберу тематику, стиль, что заходит и где просадки

            ⚠️ Канал должен быть открытым (открываться по ссылке t.me/…).
            Публикация в чужой канал без прав админа — невозможна.""";

    public static final String HELP = """
            ℹ️ <b>Помощь</b>

            <b>✍️ Контент</b> — идеи, черновики, стиль, аналитика, расписание.
            <b>🧑‍💼 Ассистент</b> — лиды в комментариях.
            <b>📈 Рост</b> — Ad Radar и исходящие.
            <b>⋯ Ещё</b> — канал, тарифы, эта справка.

            Новый канал — ссылка t.me/… в чат.""";

    public static final String ANALYSIS_IN_PROGRESS =
            "⏳ Уже работаю над вашим каналом — отчёт будет через минуту-другую.";

    public static final String FEATURE_COMING_SOON = "🚧 Эта функция появится в следующем обновлении.";

    public static String mainMenu(String channelTitle, int balance, boolean billingEnabled) {
        String channelLine = channelTitle != null
                ? "📢 Канал: " + TgHtml.b(channelTitle)
                : "📢 Канал: <i>не выбран</i> — пришлите ссылку";
        String actions = channelTitle != null
                ? """
                Выберите раздел ниже:
                • <b>✍️ Контент</b> — идеи, черновики, стиль
                • <b>🧑‍💼 Ассистент</b> — лиды в комментариях
                • <b>📈 Рост</b> — радар и исходящие"""
                : "Пришлите ссылку на канал (t.me/…) или откройте «⋯ Ещё» → помощь.";
        if (!billingEnabled) {
            return """
                    🏠 <b>Главное меню</b>

                    %s

                    %s""".formatted(channelLine, actions);
        }
        return """
                🏠 <b>Главное меню</b>

                %s
                💰 Баланс: <b>%d</b> запросов

                %s""".formatted(channelLine, balance, actions);
    }

    public static String balance(int balance, boolean billingEnabled) {
        if (!billingEnabled) {
            return """
                    🧪 Режим разработки

                    Все функции анализа сейчас бесплатны.
                    Оплата появится позже.""";
        }
        return """
                💰 Ваш баланс: %d запросов

                1 запрос = полный анализ + идеи + готовые посты.""".formatted(balance);
    }

    public static String channelConnected(String title, int subscribers, long postCount, boolean canPublish) {
        String subsLine = subscribers > 0
                ? "👥 " + formatThousands(subscribers) + " подписчиков\n"
                : "";
        String sampleLine = postCount > 0
                ? "📊 Для разбора взяли свежий срез постов канала\n"
                : "📊 Постов мало — разбор будет поверхностнее\n";
        if (canPublish) {
            return """
                    ✅ <b>Ваш канал подключён!</b>

                    📢 %s
                    %s%s
                    Бот может <b>публиковать</b> посты в этот канал.

                    Сейчас запущу разбор — дальше: идеи → черновик → «Опубликовать» в один тап 👇""".formatted(
                    TgHtml.b(title), subsLine, sampleLine);
        }
        return """
                ✅ <b>Канал на связи для разбора</b>

                📢 %s
                %s%s
                Это анализ (идеи и черновики) — <b>крючок</b>, работает и для чужих каналов.

                Чтобы <b>публиковать</b> из Pulse — подключите <b>свой</b> канал:
                бот должен быть админом с правом публикации (кнопка ниже или «⋯ Ещё» → Канал).""".formatted(
                TgHtml.b(title), subsLine, sampleLine);
    }

    private static String formatThousands(int value) {
        return String.format("%,d", value).replace(',', ' ');
    }

    public static String channelConnectFailed(String reason) {
        return "❌ <b>Не удалось подключить канал</b>\n\n" + TgHtml.esc(reason);
    }

    private BotMessages() {
    }
}

