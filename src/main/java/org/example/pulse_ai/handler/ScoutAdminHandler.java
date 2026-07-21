package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ScoutAdminHandler {

    private final PulseScoutProperties scoutProperties;
    private final ScoutAccountService scoutAccountService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public boolean canViewScout(UserEntity user) {
        return scoutProperties.isAdmin(user.getTelegramId());
    }

    public void showStatus(long chatId, int messageId, UserEntity user) {
        if (!scoutProperties.isAdmin(user.getTelegramId())) {
            send(chatId, messageId, "🔒 Статус scout доступен только админам.", keyboards.agentBackInline());
            return;
        }
        List<ScoutAccountEntity> accounts = scoutAccountService.listAll();
        StringBuilder sb = new StringBuilder("🛰 <b>Scout-инфра</b>\n\n");
        sb.append("Sidecar: ").append(scoutProperties.sidecarConfigured() ? "✅" : "❌ не настроен").append('\n');
        sb.append("Scout enabled: ").append(scoutProperties.isEnabled() ? "🟢" : "⚪️").append("\n\n");
        if (accounts.isEmpty()) {
            sb.append("Аккаунтов в БД нет. Добавьте строки в <code>scout_accounts</code> "
                    + "(OUTREACH / OBSERVER).");
        } else {
            for (ScoutAccountEntity a : accounts) {
                sb.append("• <b>").append(TgHtml.esc(a.getLabel())).append("</b> ")
                        .append(a.getAccountType()).append(" · ").append(a.getStatus())
                        .append(" · ").append(a.getSentToday()).append('/').append(a.getDailyLimit())
                        .append("\n");
                if (a.getLastError() != null) {
                    sb.append("<i>").append(TgHtml.esc(a.getLastError())).append("</i>\n");
                }
            }
        }
        send(chatId, messageId, sb.toString(), keyboards.agentBackInline());
    }

    private void send(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }
}
