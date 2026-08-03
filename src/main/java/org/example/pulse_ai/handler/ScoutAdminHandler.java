package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.outreach.OutreachTemplateService;
import org.example.pulse_ai.domain.scout.ScoutAccountHealthInfo;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
import org.example.pulse_ai.domain.scout.ScoutActionLogService;
import org.example.pulse_ai.domain.scout.ScoutSidecarHealthService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachMessageTemplateEntity;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.entity.ScoutActionLogEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.OutreachCampaignRepository;
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
    private final ScoutSidecarHealthService sidecarHealth;
    private final ScoutActionLogService actionLogService;
    private final OutreachTemplateService templateService;
    private final OutreachCampaignRepository campaignRepository;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public boolean canViewScout(UserEntity user) {
        return scoutProperties.isAdmin(user.getTelegramId());
    }

    public void handle(long chatId, int messageId, UserEntity user, String callbackData) {
        if (!canViewScout(user)) {
            send(chatId, messageId, "🔒 Только для админов Pulse.", keyboards.agentBackInline());
            return;
        }
        if (callbackData.equals(CallbackData.AGENT_SCOUT_STATUS)
                || callbackData.equals(CallbackData.AGENT_SCOUT_ADMIN)) {
            showStatus(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_SCOUT_LOGS)) {
            showLogs(chatId, messageId);
        } else if (callbackData.equals(CallbackData.AGENT_SCOUT_TEMPLATES)) {
            showTemplates(chatId, messageId);
        } else if (callbackData.equals(CallbackData.AGENT_SCOUT_KEYWORDS)) {
            showKeywords(chatId, messageId);
        } else if (callbackData.startsWith(CallbackData.AGENT_SCOUT_PAUSE)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_SCOUT_PAUSE.length()));
            scoutAccountService.pause(id);
            showStatus(chatId, messageId, user);
        } else if (callbackData.startsWith(CallbackData.AGENT_SCOUT_RESUME)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_SCOUT_RESUME.length()));
            scoutAccountService.resume(id);
            showStatus(chatId, messageId, user);
        } else if (callbackData.startsWith(CallbackData.AGENT_SCOUT_CAMPAIGN_PAUSE)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_SCOUT_CAMPAIGN_PAUSE.length()));
            campaignRepository.findById(id).ifPresent(c -> {
                c.setStatus("PAUSED");
                campaignRepository.save(c);
            });
            showStatus(chatId, messageId, user);
        } else {
            showStatus(chatId, messageId, user);
        }
    }

    public void showStatus(long chatId, int messageId, UserEntity user) {
        if (!scoutProperties.isAdmin(user.getTelegramId())) {
            send(chatId, messageId, "🔒 Статус scout доступен только админам.", keyboards.agentBackInline());
            return;
        }
        List<ScoutAccountEntity> accounts = scoutAccountService.listAll();
        StringBuilder sb = new StringBuilder("🛰 <b>Pulse Admin · Скауты</b>\n\n");
        appendSidecarStatus(sb);
        sb.append("Scout: ").append(scoutProperties.isEnabled() ? "🟢" : "⚪️").append("\n\n");
        if (accounts.isEmpty()) {
            sb.append("Аккаунтов в БД нет.\n");
        } else {
            for (ScoutAccountEntity a : accounts) {
                sb.append("• <b>").append(TgHtml.esc(a.getLabel())).append("</b> #").append(a.getId())
                        .append(" · ").append(a.getAccountType()).append(" · ").append(a.getStatus())
                        .append(" · ").append(a.getSentToday()).append('/').append(a.getDailyLimit())
                        .append("\n");
                if (a.getLastError() != null) {
                    sb.append("<i>").append(TgHtml.esc(a.getLastError())).append("</i>\n");
                }
            }
        }
        List<OutreachCampaignEntity> running = campaignRepository.findByStatus("RUNNING");
        if (!running.isEmpty()) {
            sb.append("\n<b>RUNNING кампании:</b> ").append(running.size()).append('\n');
        }
        send(chatId, messageId, sb.toString(), keyboards.scoutAdminInline(accounts));
    }

    private void showLogs(long chatId, int messageId) {
        List<ScoutActionLogEntity> logs = actionLogService.recent();
        StringBuilder sb = new StringBuilder("📜 <b>Лог скаутов</b> (последние)\n\n");
        if (logs.isEmpty()) {
            sb.append("Пока пусто — после DM / parse / scan появятся записи.");
        } else {
            for (ScoutActionLogEntity e : logs.stream().limit(15).toList()) {
                sb.append("• ").append(e.getStatus()).append(' ').append(e.getAction());
                if (e.getScoutAccountId() != null) {
                    sb.append(" #").append(e.getScoutAccountId());
                }
                if (e.getPayload() != null) {
                    sb.append(" — ").append(TgHtml.esc(trim(e.getPayload(), 80)));
                }
                if (e.getErrorText() != null) {
                    sb.append(" <i>").append(TgHtml.esc(trim(e.getErrorText(), 60))).append("</i>");
                }
                sb.append('\n');
            }
        }
        send(chatId, messageId, sb.toString(), keyboards.scoutAdminBackInline());
    }

    private void showTemplates(long chatId, int messageId) {
        List<OutreachMessageTemplateEntity> templates = templateService.listAll();
        StringBuilder sb = new StringBuilder("📝 <b>Шаблоны рассылок</b>\n\n");
        sb.append("Переменные: <code>{username}</code> <code>{channel}</code> <code>{topic}</code>\n\n");
        if (templates.isEmpty()) {
            sb.append("Шаблонов нет.");
        } else {
            for (OutreachMessageTemplateEntity t : templates.stream().limit(8).toList()) {
                sb.append("• <b>").append(TgHtml.esc(t.getName())).append("</b> [")
                        .append(t.getScenario()).append("]\n")
                        .append(TgHtml.esc(trim(t.getBody(), 120))).append("\n\n");
            }
        }
        sb.append("<i>Править удобнее в веб-админке: /admin</i>");
        send(chatId, messageId, sb.toString(), keyboards.scoutAdminBackInline());
    }

    private void showKeywords(long chatId, int messageId) {
        StringBuilder sb = new StringBuilder("🔎 <b>Radar keywords</b>\n\n");
        for (String kw : scoutProperties.getRadarKeywords()) {
            sb.append("• ").append(TgHtml.esc(kw)).append('\n');
        }
        sb.append("\n<i>Сейчас из конфига pulse.scout.radar-keywords</i>");
        send(chatId, messageId, sb.toString(), keyboards.scoutAdminBackInline());
    }

    private void appendSidecarStatus(StringBuilder sb) {
        try {
            if (!scoutProperties.sidecarConfigured()) {
                sb.append("Sidecar: ❌ URL не задан\n");
                return;
            }
            sb.append("Sidecar: ").append(TgHtml.esc(scoutProperties.getSidecarUrl())).append('\n');
            ScoutSidecarHealthService.HealthStatus health = sidecarHealth.ping();
            if (!health.reachable()) {
                sb.append("Ping: 🔴 ").append(TgHtml.esc(health.detail())).append('\n');
                return;
            }
            sb.append("Ping: 🟢 online");
            if (!health.accounts().isEmpty()) {
                sb.append(" · sessions: ").append(health.accounts().size());
            }
            sb.append('\n');
            for (ScoutAccountHealthInfo acc : health.accounts()) {
                sb.append("  ↳ id=").append(acc.id()).append(' ')
                        .append(TgHtml.esc(acc.label())).append(" (").append(acc.type()).append(")\n");
            }
        } catch (Throwable t) {
            sb.append("Ping: ⚠️ ").append(TgHtml.esc(t.getClass().getSimpleName())).append('\n');
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void send(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }
}
