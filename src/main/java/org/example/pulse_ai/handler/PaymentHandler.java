package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseBillingProperties;
import org.example.pulse_ai.domain.entitlement.AssistantQuotaService;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.domain.payment.PackageKind;
import org.example.pulse_ai.domain.payment.PaymentService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.example.pulse_ai.persistence.entity.PaymentEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.persistence.repository.UserRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.SalesCopy;
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
    private final EntitlementService entitlementService;
    private final AssistantQuotaService assistantQuotaService;

    public void showPackages(long chatId) {
        showPackages(chatId, null);
    }

    public void showPackages(long chatId, UserEntity user) {
        if (!billingProperties.isEnabled()) {
            messageSender.sendText(chatId, """
                    🧪 <b>Режим тестирования</b>

                    Сейчас всё открыто — разборы, идеи, ассистент.
                    Оплата включится, когда будем готовы к запуску.""");
            return;
        }
        List<PackageEntity> packages = packageRepository.findByActiveTrueOrderBySortOrderAsc();
        if (packages.isEmpty()) {
            messageSender.sendText(chatId, "Пакеты временно недоступны.");
            return;
        }
        boolean subscribed = user != null && entitlementService.hasAccess(user.getId(), PerkType.MANAGER);
        AssistantQuotaService.DmQuotaSnapshot dm = user != null
                ? assistantQuotaService.dmQuota(user.getId())
                : null;
        StringBuilder text = new StringBuilder(SalesCopy.catalogIntro(subscribed, dm));
        text.append("\n\n");
        for (PackageEntity pack : packages) {
            PackageKind kind = PackageKind.from(pack.getKind());
            if (kind == PackageKind.LS_TOPUP && !subscribed) {
                continue;
            }
            boolean highlight = "CONTENT".equals(pack.getCode()) || "ASSIST_PLUS".equals(pack.getCode());
            text.append(SalesCopy.packageLine(pack, highlight)).append("\n\n");
        }
        messageSender.sendTextWithInline(
                chatId,
                text.toString().trim(),
                keyboards.paymentCatalogInline(packages, subscribed)
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
        PackageKind kind = PackageKind.from(pack.getKind());
        if (kind == PackageKind.LS_TOPUP && !entitlementService.hasAccess(user.getId(), PerkType.MANAGER)) {
            messageSender.sendTextWithInline(chatId,
                    "🔒 Допы ЛС — только при активной подписке Pulse Ассистент.\n\n"
                            + "Сначала оформите тариф 3990 / 6990 / 9990 ₽.",
                    keyboards.paymentCatalogInline(
                            packageRepository.findByActiveTrueOrderBySortOrderAsc(), false));
            return;
        }

        PaymentEntity payment;
        try {
            payment = paymentService.createStarsPayment(user, packageId);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            messageSender.sendText(chatId, "❌ " + ex.getMessage());
            return;
        }
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
                    PackageKind kind = pack != null ? PackageKind.from(pack.getKind()) : PackageKind.ANALYSIS;

                    if (kind == PackageKind.ASSISTANT) {
                        AssistantQuotaService.DmQuotaSnapshot dm = assistantQuotaService.dmQuota(user.getId());
                        messageSender.sendTextWithInline(
                                user.getTelegramId(),
                                SalesCopy.assistantPaymentSuccess(packName, dm),
                                keyboards.agentBackInline()
                        );
                        return;
                    }
                    if (kind == PackageKind.LS_TOPUP) {
                        AssistantQuotaService.DmQuotaSnapshot dm = assistantQuotaService.dmQuota(user.getId());
                        messageSender.sendTextWithInline(
                                user.getTelegramId(),
                                SalesCopy.lsTopupSuccess(packName, pack != null ? pack.getDmQuota() : 0, dm),
                                keyboards.agentBackInline()
                        );
                        return;
                    }

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

    private static short parsePackageId(String callbackData) {
        String idStr = callbackData.substring((CallbackData.PREFIX_PAY + "pack:").length());
        return Short.parseShort(idStr);
    }
}
