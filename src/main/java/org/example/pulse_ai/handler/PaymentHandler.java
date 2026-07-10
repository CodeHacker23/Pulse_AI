package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AnalysisRequestRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.BotMessages;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentHandler {

    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;

    public void showPackages(long chatId) {
        if (!billingProperties.isEnabled()) {
            messageSender.sendText(chatId, """
                    🧪 Сейчас всё бесплатно — тестируем функционал.
                    Оплата появится позже.""");
            return;
        }
        messageSender.sendTextWithInline(
                chatId,
                """
                        💳 Выберите пакет

                        • 🌱 Старт — 10 запросов — 990 ₽ (~99 ₽/запрос)
                        • ⭐ Контент — 18 запросов — 1 600 ₽ (~89 ₽/запрос)
                        • 🚀 Про — 30 запросов — 2 300 ₽ (~76 ₽/запрос)

                        1 запрос = полный анализ + идеи + готовые посты + публикация""",
                keyboards.paymentPackagesInline()
        );
    }

    public void selectPackage(long chatId, UserEntity user, String callbackData) {
        messageSender.sendText(
                chatId,
                BotMessages.FEATURE_COMING_SOON + "\n\nОплата будет подключена на неделе 5 roadmap."
        );
    }
}
