package org.example.pulse_ai.text;

public final class BotMessages {

    public static final String WELCOME = """
            👋 Привет! Это Pulse AI — аналитик Telegram-каналов.

            Пришлите ссылку на любой открытый канал — я разберу его по полочкам:
            📊 метрики: просмотры, вовлечённость, лучшее время
            🧠 разбор: о чём канал, стиль, что заходит, где проседает
            💡 идеи: что публиковать, чтобы росли охваты

            Никаких настроек и прав администратора — просто ссылка.

            👇 Нажмите «Анализировать канал» или сразу пришлите ссылку.""";

    public static final String HOW_IT_WORKS = """
            💡 <b>Как это работает — 3 шага</b>

            1️⃣ <b>Пришлите ссылку на канал</b>
                Например: https://t.me/durov или @durov

            2️⃣ <b>Я анализирую данные</b>
                Посты, просмотры и метрики — беру на себя

            3️⃣ <b>Вы получаете отчёт</b>
                Графики, цифры, разбор контента и конкретные идеи — за 1–3 минуты

            ✅ Работает с любым открытым каналом.""";

    public static final String CONNECT_CHANNEL = """
            🔍 Пришлите ссылку на канал

            Просто отправьте ссылку или @username открытого канала:
            • https://t.me/durov
            • @durov

            Дальше я всё сделаю сам:
            📥 соберу посты и просмотры
            📊 подтяну метрики с TGStat / Telemetr / Telega.in
            🧠 разберу тематику, стиль, что заходит и где просадки

            ⚠️ Канал должен быть открытым (открываться по ссылке t.me/…).""";

    public static final String HELP = """
            ℹ️ Помощь

            ❓ Как запустить анализ?
            → Нажмите «Анализ канала» и пришлите ссылку на открытый канал.

            ❓ Нужны ли права администратора?
            → Нет. Я анализирую любой открытый канал по ссылке.

            ❓ Что я анализирую?
            → Посты, просмотры, вовлечённость, время, тематику и стиль.
            → Плюс метрики с TGStat, Telemetr и Telega.in.

            ❓ Не находит посты?
            → Убедитесь, что канал открытый и открывается по ссылке t.me/…""";

    public static final String ANALYSIS_IN_PROGRESS =
            "⏳ Уже работаю над вашим каналом — отчёт будет через минуту-другую.";

    public static final String FEATURE_COMING_SOON = "🚧 Эта функция появится в следующем обновлении.";

    public static String mainMenu(String channelTitle, int balance, boolean billingEnabled) {
        String channelLine = channelTitle != null
                ? "📢 Канал: " + TgHtml.b(channelTitle)
                : "📢 Канал: <i>не подключён</i>";
        if (!billingEnabled) {
            return """
                    🏠 <b>Главное меню</b>

                    %s
                    🧪 Тестовый режим — всё бесплатно

                    Пришлите ссылку на канал или выберите действие 👇""".formatted(channelLine);
        }
        return """
                🏠 <b>Главное меню</b>

                %s
                💰 Баланс: <b>%d</b> запросов

                Пришлите ссылку на канал или выберите действие 👇""".formatted(channelLine, balance);
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

    public static String channelConnected(String title, int subscribers, long postCount) {
        String subsLine = subscribers > 0
                ? "👥 " + formatThousands(subscribers) + " подписчиков\n"
                : "";
        return """
                ✅ <b>Канал на связи!</b>

                📢 %s
                %s
                Дальше я всё сделаю сам:
                📊 соберу метрики и охваты
                🧲 разберу, что цепляет, а что нет
                💡 подкину идеи под ваш стиль

                Жмите «🚀 Запустить анализ» 👇""".formatted(TgHtml.b(title), subsLine);
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

