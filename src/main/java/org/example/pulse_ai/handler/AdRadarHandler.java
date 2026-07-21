package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.radar.AdPlacementQualityService;
import org.example.pulse_ai.domain.radar.AdRadarService;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.AdWatchSourceEntity;
import org.example.pulse_ai.persistence.entity.AdRadarHitEntity;
import org.example.pulse_ai.persistence.repository.AdRadarHitRepository;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.Optional;

/** Jarvis P1.5 — Ad Radar: мониторинг чатов и скоринг площадок. */
@Component
@RequiredArgsConstructor
public class AdRadarHandler {

    private final AdRadarService adRadarService;
    private final AdRadarHitRepository hitRepository;
    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void showMenu(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        long watchCount = adRadarService.activeWatches(user.getId()).size();
        long placeCount = adRadarService.recentPlacements(user.getId()).size();

        StringBuilder sb = new StringBuilder();
        sb.append("📡 <b>Ad Radar — поиск рекламы</b>\n\n");
        sb.append("Jarvis помогает находить <b>где покупать рекламу</b> и не слить бюджет на мёртвые каналы.\n\n");
        sb.append("• <b>Чаты на мониторинг</b> — сохраняете группу; позже observer ловит «реклама», «прайс» (P2).\n");
        sb.append("• <b>Проверка площадки</b> — живой / мёртвый / спам по постам канала.\n\n");
        if (channelOpt.isPresent()) {
            sb.append("Ваш канал: «").append(TgHtml.esc(channelOpt.get().getTitle())).append("»\n");
        } else {
            sb.append("⚠️ Подключите свой канал — так Jarvis точнее подберёт площадки.\n");
        }
        sb.append("Сохранено: <b>").append(watchCount).append("</b> чатов · <b>")
                .append(placeCount).append("</b> проверок");

        editOrSend(chatId, messageId, sb.toString(), keyboards.adRadarMenuInline());
    }

    public void showWatches(long chatId, int messageId, UserEntity user) {
        List<AdWatchSourceEntity> watches = adRadarService.activeWatches(user.getId());
        if (watches.isEmpty()) {
            editOrSend(chatId, messageId,
                    "📋 <b>Чаты на мониторинг</b>\n\nПока пусто.\n"
                            + "Добавьте ссылку на чат/группу — Jarvis сохранит её для observer-сети.",
                    keyboards.adRadarMenuInline());
            return;
        }
        StringBuilder sb = new StringBuilder("📋 <b>Чаты на мониторинг</b>\n\n");
        int n = 1;
        for (AdWatchSourceEntity w : watches) {
            sb.append(n++).append(". ").append(TgHtml.esc(w.getLinkOrUsername()));
            if (w.getTitle() != null && !w.getTitle().equals(w.getLinkOrUsername())) {
                sb.append(" · ").append(TgHtml.esc(w.getTitle()));
            }
            sb.append("\n");
        }
        sb.append("\n<i>Авто-мониторинг ключевых слов подключим в P2 (observer).</i>");
        editOrSend(chatId, messageId, sb.toString(), keyboards.adRadarMenuInline());
    }

    public void showPlacements(long chatId, int messageId, UserEntity user) {
        List<AdPlacementEntity> places = adRadarService.recentPlacements(user.getId());
        if (places.isEmpty()) {
            editOrSend(chatId, messageId,
                    "🔍 <b>Проверенные площадки</b>\n\nПока пусто.\n"
                            + "Пришлите @канал — Jarvis проверит: живой, мёртвый или спам.",
                    keyboards.adRadarMenuInline());
            return;
        }
        StringBuilder sb = new StringBuilder("🔍 <b>Проверенные площадки</b>\n\n");
        for (AdPlacementEntity p : places) {
            sb.append(AdPlacementQualityService.verdictLabel(p.getQualityVerdict()))
                    .append(" <b>@").append(TgHtml.esc(p.getTargetUsername())).append("</b>");
            if (p.getQualityScore() != null) {
                sb.append(" · ").append(p.getQualityScore()).append("/100");
            }
            sb.append("\n");
            if (p.getQualityNotes() != null) {
                sb.append("<i>").append(TgHtml.esc(p.getQualityNotes())).append("</i>\n");
            }
            sb.append("\n");
        }
        editOrSend(chatId, messageId, sb.toString().trim(),
                keyboards.adRadarPlacementsInline(places));
    }

    public void promptAddWatch(long chatId, UserEntity user) {
        sessionService.getOrCreate(chatId).setState(BotState.AD_RADAR_WATCH_INPUT);
        messageSender.sendTextWithInlineSafe(chatId,
                "➕ <b>Добавить чат на мониторинг</b>\n\n"
                        + "Пришлите ссылку или @username чата/группы, где бывает реклама.\n"
                        + "Пример: <code>t.me/marketing_chat</code> или <code>@channel</code>",
                keyboards.agentBackInline());
    }

    public void promptAddPlace(long chatId, UserEntity user) {
        sessionService.getOrCreate(chatId).setState(BotState.AD_RADAR_PLACE_INPUT);
        messageSender.sendTextWithInlineSafe(chatId,
                "🔎 <b>Проверить площадку</b>\n\n"
                        + "Пришлите @username <b>публичного канала</b>, где хотите купить рекламу.\n"
                        + "Jarvis проверит активность и долю рекламных постов.",
                keyboards.agentBackInline());
    }

    public void handleWatchInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        Long ownerChannelId = userService.findActiveChannel(user).map(ChannelEntity::getId).orElse(null);
        try {
            AdWatchSourceEntity saved = adRadarService.addWatchSource(user, ownerChannelId, raw);
            messageSender.sendTextWithInlineSafe(chatId,
                    "✅ Чат добавлен: <b>" + TgHtml.esc(saved.getLinkOrUsername()) + "</b>\n\n"
                            + "Когда observer-сеть заработает — пришлём сигнал «реклама/прайс».",
                    keyboards.adRadarMenuInline());
        } catch (IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "Не удалось добавить. Проверьте ссылку.");
        }
    }

    public void handlePlaceInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        messageSender.sendTextSafe(chatId, "⏳ Проверяю канал…");
        Long ownerChannelId = userService.findActiveChannel(user).map(ChannelEntity::getId).orElse(null);
        try {
            AdPlacementEntity saved = adRadarService.checkAndSavePlacement(user, ownerChannelId, raw);
            String text = AdPlacementQualityService.verdictLabel(saved.getQualityVerdict())
                    + " <b>@" + TgHtml.esc(saved.getTargetUsername()) + "</b>\n"
                    + "Оценка: <b>" + saved.getQualityScore() + "/100</b>\n\n"
                    + TgHtml.esc(saved.getQualityNotes() != null ? saved.getQualityNotes() : "");
            messageSender.sendTextWithInlineSafe(chatId, text, keyboards.adRadarMenuInline());
        } catch (IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "Не удалось проверить канал. Нужен публичный @username.");
        }
    }

    public void recheck(long chatId, int messageId, UserEntity user, long placementId) {
        messageSender.editTextSafe(chatId, messageId, "⏳ Обновляю проверку…");
        adRadarService.recheckPlacement(user.getId(), placementId).ifPresentOrElse(
                saved -> showPlacements(chatId, messageId, user),
                () -> messageSender.sendTextSafe(chatId, "Площадка не найдена."));
    }

    public void handle(long chatId, int messageId, String callbackData, UserEntity user) {
        if (callbackData.equals(CallbackData.AGENT_RADAR)) {
            showMenu(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_RADAR_WATCHES)) {
            showWatches(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_RADAR_PLACES)) {
            showPlacements(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_RADAR_ADD_WATCH)) {
            promptAddWatch(chatId, user);
        } else if (callbackData.equals(CallbackData.AGENT_RADAR_ADD_PLACE)) {
            promptAddPlace(chatId, user);
        } else if (callbackData.equals(CallbackData.AGENT_RADAR_HITS)) {
            showHits(chatId, messageId, user);
        } else if (callbackData.startsWith(CallbackData.AGENT_RADAR_RECHECK)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_RADAR_RECHECK.length()));
            recheck(chatId, messageId, user, id);
        } else {
            showMenu(chatId, messageId, user);
        }
    }

    public void showHits(long chatId, int messageId, UserEntity user) {
        List<AdRadarHitEntity> hits = hitRepository.findTop10ByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "NEW");
        if (hits.isEmpty()) {
            editOrSend(chatId, messageId,
                    "📣 <b>Сигналы Ad Radar</b>\n\nПока пусто.\n"
                            + "Добавьте чаты на мониторинг — observer пришлёт сигнал, когда найдёт «реклама/прайс».",
                    keyboards.adRadarMenuInline());
            return;
        }
        StringBuilder sb = new StringBuilder("📣 <b>Сигналы Ad Radar</b>\n\n");
        for (AdRadarHitEntity h : hits) {
            sb.append("• ").append(TgHtml.esc(h.getSnippet() != null ? h.getSnippet() : "сигнал")).append("\n\n");
        }
        editOrSend(chatId, messageId, sb.toString().trim(), keyboards.adRadarMenuInline());
    }

    private void editOrSend(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editText(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }
}
