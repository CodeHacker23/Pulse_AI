package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.payment.PaymentService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.example.pulse_ai.persistence.entity.PaymentEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.SalesCopy;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentHandler {

    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PulseBillingProperties billingProperties;
    private final PackageRepository packageRepository;
    private final PaymentService paymentService;
    private final UserRepository userRepository;
    private final PerkHandler perkHandler;

    public void showPackages(long chatId) {
        if (!billingProperties.isEnabled()) {
            messageSender.sendText(chatId, """
                    🧪 <b>Режим тестирования</b>

                    Сейчас всё открыто — разборы, идеи, бонусы.
                    Оплата включится, когда будем готовы к запуску.""");
            return;
        }
        List<PackageEntity> packages = packageRepository.findByActiveTrueOrderBySortOrderAsc();
        if (packages.isEmpty()) {
            messageSender.sendText(chatId, "Пакеты временно недоступны.");
            return;
        }
        messageSender.sendTextWithInline(
                chatId,
                buildPackagesText(packages),
                keyboards.paymentPackagesInline(packages)
        );
    }

    public void selectPackage(long chatId, UserEntity user, String callbackData) {
        if (!billingProperties.isEnabled()) {
            messageSender.sendText(chatId, "Оплата отключена в тестовом режиме.");
            return;
        }
        short packageId = parsePackageId(callbackData);
        PackageEntity pack = packageRepository.findById(packageId).orElse(null);
        if (pack == null || !pack.isActive() || pack.getStarsAmount() == null) {
            messageSender.sendText(chatId, "❌ Пакет не найден.");
            return;
        }

        PaymentEntity payment = paymentService.createStarsPayment(user, packageId);
        String payload = PaymentService.payloadFor(payment.getId());
        String title = "Pulse AI — " + pack.getName();
        String description = SalesCopy.invoiceDescription(pack);

        boolean sent = messageSender.sendStarsInvoice(
                chatId,
                title,
                description,
                payload,
                pack.getStarsAmount()
        );
        if (!sent) {
            messageSender.sendText(chatId, "❌ Не удалось открыть оплату. Попробуйте позже.");
        }
    }

    public void handlePreCheckout(String preCheckoutQueryId, String invoicePayload, long telegramUserId) {
        boolean ok = paymentService.validatePayload(invoicePayload, telegramUserId);
        messageSender.answerPreCheckout(
                preCheckoutQueryId,
                ok,
                ok ? null : "Платёж недействителен или уже обработан."
        );
    }

    public void handleSuccessfulPayment(UserEntity user, SuccessfulPayment payment) {
        paymentService.completeStarsPayment(
                payment.getInvoicePayload(),
                payment.getTelegramPaymentChargeId(),
                payment.getTotalAmount()
        ).ifPresentOrElse(
                completed -> {
                    PackageEntity pack = packageRepository.findById(completed.getPackageId()).orElse(null);
                    String packName = pack != null ? pack.getName() : "пакет";
                    int credited = completed.getRequestsCredited() != null ? completed.getRequestsCredited() : 0;
                    int balance = userRepository.findById(user.getId()).map(UserEntity::getBalance).orElse(0);

                    if (completed.getPerksRemainingToPick() > 0) {
                        messageSender.sendTextWithInline(
                                user.getTelegramId(),
                                SalesCopy.paymentSuccessChoosePerks(
                                        packName, credited, balance, completed.getPerksRemainingToPick()),
                                keyboards.backToMainInline()
                        );
                        perkHandler.showPickerIfNeeded(user.getTelegramId(), user, completed);
                    } else {
                        messageSender.sendTextWithInline(
                                user.getTelegramId(),
                                SalesCopy.paymentSuccessNoPerks(packName, credited, balance),
                                keyboards.featureHubInline()
                        );
                    }
                },
                () -> messageSender.sendTextSafe(
                        user.getTelegramId(),
                        "❌ Не удалось зачислить оплату. Напишите в поддержку с чеком из Telegram."
                )
        );
    }

    private static String buildPackagesText(List<PackageEntity> packages) {
        StringBuilder sb = new StringBuilder(SalesCopy.packagesIntro());
        sb.append("\n\n");
        for (PackageEntity pack : packages) {
            boolean highlight = "CONTENT".equals(pack.getCode());
            sb.append(SalesCopy.packageLine(pack, highlight)).append("\n\n");
        }
        sb.append("<i>⭐ «Оптимал» — самый частый выбор: баланс цены и бонусов.</i>");
        return sb.toString().trim();
    }

    private static short parsePackageId(String callbackData) {
        String idStr = callbackData.substring((CallbackData.PREFIX_PAY + "pack:").length());
        return Short.parseShort(idStr);
    }
}
