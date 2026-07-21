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
            📢 <b>Подключение своего канала</b>

            Нужно, чтобы бот мог <b>публиковать</b> ваши черновики.

            1. Откройте настройки канала → Администраторы
            2. Добавьте бота с правом <b>«Публикация сообщений»</b>
            3. Перешлите сюда <b>любой пост</b> из этого канала

            Анализировать чужие каналы можно просто по ссылке — без этого шага.""";

    public static final String CONNECT_CHANNEL = """
            🔍 <b>Пришлите ссылку на канал</b>

            Просто отправьте ссылку или @username открытого канала:
            • <code>https://t.me/durov</code>
            • <code>@durov</code>

            Дальше я всё сделаю сам:
            📥 соберу посты и просмотры
            📊 подтяну метрики
            🧠 разберу тематику, стиль, что заходит и где просадки

            ⚠️ Канал должен быть открытым (открываться по ссылке t.me/…).""";

    public static final String HELP = """
            ℹ️ <b>Помощь</b>

            <b>✍️ Контент</b> — идеи и черновики по уже сделанному разбору (без повторного анализа).
            <b>📊 Аналитика</b> — отчёты, разбор, новый анализ.
            <b>📅 Расписание</b> — посты, которые бот опубликует сам.
            <b>🧑‍💼 Менеджер</b> — ловит горячие лиды в комментариях.
            <b>⚙️ Канал</b> — подключить / сменить канал для публикации.

            Новый канал — просто пришлите ссылку t.me/… в чат.""";

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
                • <b>✍️ Контент</b> — идеи и черновики постов
                • <b>📊 Аналитика</b> — отчёты и разбор канала
                • <b>📅 Расписание</b> — что уже запланировано
                • <b>🧑‍💼 Менеджер</b> — лиды в комментариях"""
                : "Пришлите ссылку на канал (t.me/…) или откройте «ℹ️ Помощь».";
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
        String postsLine = postCount > 0
                ? "📝 Постов в базе: <b>" + postCount + "</b>\n"
                : "📝 Канал пока без постов — <b>идеальный момент</b> начать с чистого листа.\n";
        if (canPublish) {
            return """
                    ✅ <b>Ваш канал подключён!</b>

                    📢 %s
                    %s%s
                    Бот может <b>публиковать</b> посты в этот канал.

                    Сейчас запущу разбор — дальше: идеи → черновик → «Опубликовать» в один тап 👇""".formatted(
                    TgHtml.b(title), subsLine, postsLine);
        }
        if (postCount == 0) {
            return """
                    ✅ <b>Канал на связи!</b>

                    📢 %s
                    %s%s
                    Пока мало данных для глубокого разбора — но идеи и черновики всё равно подготовлю.

                    Добавьте бота админом с правом публикации — и посты можно будет выкладывать из бота.""".formatted(
                    TgHtml.b(title), subsLine, postsLine);
        }
        return """
                ✅ <b>Канал на связи!</b>

                📢 %s
                %s%s
                Готов к анализу. Жмите «Запустить анализ» 👇""".formatted(TgHtml.b(title), subsLine, postsLine);
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

