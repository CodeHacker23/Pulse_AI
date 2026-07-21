package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.entitlement.EntitlementService;
import org.example.pulse_ai.domain.entitlement.PerkType;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.example.pulse_ai.persistence.entity.PaymentEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.example.pulse_ai.persistence.repository.PaymentRepository;
import org.example.pulse_ai.persistence.repository.UserEntitlementRepository;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.SalesCopy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerkHandler {

    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;
    private final PaymentRepository paymentRepository;
    private final PackageRepository packageRepository;
    private final EntitlementService entitlementService;
    private final UserEntitlementRepository entitlementRepository;

    public void showPickerIfNeeded(long chatId, UserEntity user, PaymentEntity payment) {
        if (payment.getPerksRemainingToPick() <= 0) {
            return;
        }
        PackageEntity pack = packageRepository.findById(payment.getPackageId()).orElse(null);
        if (pack == null) {
            return;
        }
        int total = pack.getPerkChoicesCount();
        int remaining = payment.getPerksRemainingToPick();
        String text = SalesCopy.perkPickerIntro(pack.getName(), remaining, total);
        InlineKeyboardMarkup keyboard = keyboards.perkPickerInline(
                payment.getId(), pack.getCode(), remaining, total);
        messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
    }

    public void handle(long chatId, int messageId, String callbackQueryId, UserEntity user, String callbackData) {
        // perk:pick:{paymentId}:{PERK_CODE}
        String tail = callbackData.substring((CallbackData.PREFIX_PERK + "pick:").length());
        int colon = tail.indexOf(':');
        if (colon <= 0) {
            messageSender.answerCallback(callbackQueryId);
            return;
        }
        long paymentId = Long.parseLong(tail.substring(0, colon));
        String perkCode = tail.substring(colon + 1);
        PerkType perk = PerkType.fromCode(perkCode).orElse(null);

        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || !payment.getUserId().equals(user.getId()) || payment.getPerksRemainingToPick() <= 0) {
            messageSender.answerCallbackWithAlert(callbackQueryId, "Бонус уже выбран или недоступен.");
            return;
        }
        if (perk == null) {
            messageSender.answerCallbackWithAlert(callbackQueryId, "Неизвестный бонус.");
            return;
        }

        PackageEntity pack = packageRepository.findById(payment.getPackageId()).orElse(null);
        if (pack == null || !PerkType.pickableForPackage(pack.getCode()).contains(perk)) {
            messageSender.answerCallbackWithAlert(callbackQueryId, "Этот бонус недоступен в вашем пакете.");
            return;
        }

        if (entitlementRepository.findByUserIdAndPerkCodeOrderByGrantedAtDesc(user.getId(), perk.code()).stream()
                .anyMatch(e -> paymentId == (e.getSourcePaymentId() != null ? e.getSourcePaymentId() : -1L))) {
            messageSender.answerCallbackWithAlert(callbackQueryId, "Вы уже брали этот бонус из пакета.");
            return;
        }

        messageSender.answerCallback(callbackQueryId);
        entitlementService.grant(user.getId(), perk, paymentId);

        payment.setPerksRemainingToPick((short) (payment.getPerksRemainingToPick() - 1));
        paymentRepository.save(payment);

        String response = isLivePerk(perk)
                ? SalesCopy.perkGranted(perk, payment.getPerksRemainingToPick())
                : SalesCopy.perkComingSoon(perk);

        if (payment.getPerksRemainingToPick() > 0 && pack != null) {
            InlineKeyboardMarkup keyboard = keyboards.perkPickerInline(
                    paymentId, pack.getCode(), payment.getPerksRemainingToPick(), pack.getPerkChoicesCount());
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, response, keyboard);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, response, keyboard);
            }
        } else {
            InlineKeyboardMarkup keyboard = keyboards.featureHubInline();
            if (messageId > 0) {
                messageSender.editText(chatId, messageId, response, keyboard);
            } else {
                messageSender.sendTextWithInlineSafe(chatId, response, keyboard);
            }
        }
    }

    private static boolean isLivePerk(PerkType perk) {
        return perk == PerkType.POST_AUDIT
                || perk == PerkType.HARD_AUDIT
                || perk == PerkType.DIGEST
                || perk == PerkType.COMPETITOR
                || perk == PerkType.SELLING
                || perk == PerkType.MANAGER;
    }
}
