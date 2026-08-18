package org.example.pulse_ai.handler;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.domain.radar.AdDealService;
import org.example.pulse_ai.domain.radar.AdPlacementMatchService;
import org.example.pulse_ai.domain.radar.AdPlacementMatchService.MatchResult;
import org.example.pulse_ai.domain.radar.AdPlacementMatchService.ScoredPlacement;
import org.example.pulse_ai.domain.radar.AdPlacementQualityService;
import org.example.pulse_ai.domain.radar.AdRadarService;
import org.example.pulse_ai.domain.user.UserService;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.persistence.entity.AdDealEntity;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.AdRadarHitEntity;
import org.example.pulse_ai.persistence.entity.AdWatchSourceEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AdPlacementRepository;
import org.example.pulse_ai.persistence.repository.AdRadarHitRepository;
import org.example.pulse_ai.session.BotState;
import org.example.pulse_ai.session.UserSession;
import org.example.pulse_ai.session.UserSessionService;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.Optional;

/**
 * Площадки для рекламы: один экран → «Начать поиск» → список кандидатов.
 * Проверка @канала вручную — в «⋯ Ещё». Сигналы/чаты — скрыты из основного UI.
 */
@Component
@RequiredArgsConstructor
public class AdRadarHandler {

    private final AdRadarService adRadarService;
    private final AdPlacementMatchService matchService;
    private final AdDealService dealService;
    private final AdDealHandler adDealHandler;
    private final AdPlacementRepository placementRepository;
    private final AdRadarHitRepository hitRepository;
    private final UserService userService;
    private final UserSessionService sessionService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    public void showMenu(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        StringBuilder sb = new StringBuilder();
        sb.append("📡 <b>Площадки для рекламы</b>\n\n");
        sb.append("Сначала читаю ваши посты → кто ЦА → ищу площадки.\n\n");
        sb.append("• Подберу каналы темы\n");
        sb.append("• Дальше: креатив и бриф админу\n\n");
        if (channelOpt.isPresent()) {
            ChannelEntity c = channelOpt.get();
            sb.append("Фокус: «").append(TgHtml.esc(c.getTitle())).append("»");
            if (c.getCategory() != null && !c.getCategory().isBlank()) {
                sb.append(" · ").append(TgHtml.esc(c.getCategory()));
            }
        } else {
            sb.append("⚠️ Сначала разберите канал (ссылка → анализ) — иначе не из чего брать нишу.");
        }
        editOrSend(chatId, messageId, sb.toString().trim(), keyboards.adRadarMenuInline());
    }

    public void showMatch(long chatId, int messageId, UserEntity user) {
        Optional<ChannelEntity> channelOpt = userService.findActiveChannel(user);
        if (channelOpt.isEmpty()) {
            editOrSend(chatId, messageId,
                    "📡 <b>Площадки для рекламы</b>\n\n"
                            + "Сначала пришлите ссылку на канал и дождитесь разбора — нужна ниша.",
                    keyboards.adRadarMenuInline());
            return;
        }
        editOrSend(chatId, messageId,
                "📡 <b>Площадки для рекламы</b>\n\n⏳ Ищу каналы под вашу нишу…",
                null);

        MatchResult result = matchService.matchForChannel(user, channelOpt.get());
        if (result.isEmpty()) {
            String detail = result.emptyMessage() != null ? result.emptyMessage()
                    : "Кандидатов не нашлось. Попробуйте позже или проверьте @канал в «⋯ Ещё».";
            editOrSend(chatId, messageId,
                    "📡 <b>Площадки для рекламы</b>\n\n" + detail,
                    keyboards.adRadarMenuInline());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📡 <b>Площадки</b>\n");
        if (result.audienceLine() != null && !result.audienceLine().isBlank()) {
            sb.append("<i>").append(TgHtml.esc(result.audienceLine())).append("</i>\n\n");
        } else {
            sb.append("<i>запрос: ").append(TgHtml.esc(result.category())).append("</i>\n\n");
        }
        int i = 1;
        for (ScoredPlacement sp : result.placements()) {
            AdPlacementEntity p = sp.placement();
            sb.append(i++).append(". <b>@").append(TgHtml.esc(p.getTargetUsername())).append("</b>");
            if (p.getTargetTitle() != null) {
                sb.append(" — ").append(TgHtml.esc(shorten(p.getTargetTitle(), 40)));
            }
            sb.append('\n');
            if (sp.subscribersHint() > 0) {
                sb.append("   👥 ~").append(formatNum(sp.subscribersHint()));
            }
            if (p.getAvgViews() != null && p.getAvgViews() > 0) {
                if (sp.subscribersHint() > 0) {
                    sb.append(" · ");
                } else {
                    sb.append("   ");
                }
                sb.append("👁 ~").append(formatNum(p.getAvgViews()));
            }
            sb.append("\n\n");
        }
        sb.append("Нажмите канал — креатив или сделка.");
        editOrSend(chatId, messageId, sb.toString().trim(),
                keyboards.adRadarMatchInline(result.placements().stream().map(ScoredPlacement::placement).toList()));
    }

    public void showPlacementCard(long chatId, int messageId, UserEntity user, long placementId) {
        AdPlacementEntity p = placementRepository.findByIdAndUserId(placementId, user.getId()).orElse(null);
        if (p == null) {
            messageSender.sendTextSafe(chatId, "Площадка не найдена.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(AdPlacementQualityService.verdictLabel(p.getQualityVerdict()))
                .append(" <b>@").append(TgHtml.esc(p.getTargetUsername())).append("</b>\n");
        if (p.getTargetTitle() != null) {
            sb.append(TgHtml.esc(p.getTargetTitle())).append('\n');
        }
        sb.append("\n");
        if (p.getAvgViews() != null) {
            sb.append("Охват ~").append(formatNum(p.getAvgViews())).append(" просм.\n");
        }
        if (p.getQualityNotes() != null) {
            sb.append("\n<i>").append(TgHtml.esc(shorten(p.getQualityNotes(), 200))).append("</i>\n");
        }
        sb.append("\n<i>Дальше: оформить сделку или только креатив.</i>");
        editOrSend(chatId, messageId, sb.toString().trim(), keyboards.adRadarPlacementCardInline(p.getId()));
    }

    public void generateCreative(long chatId, int messageId, UserEntity user, long placementId) {
        Optional<ChannelEntity> ownerOpt = userService.findActiveChannel(user);
        AdPlacementEntity p = placementRepository.findByIdAndUserId(placementId, user.getId()).orElse(null);
        if (ownerOpt.isEmpty() || p == null) {
            messageSender.sendTextSafe(chatId, "Нужен ваш канал и площадка.");
            return;
        }
        editOrSend(chatId, messageId, "⏳ Пишу рекламный пост под @" + TgHtml.esc(p.getTargetUsername()) + "…", null);
        AdDealEntity deal = dealService.openOrGet(user, ownerOpt.get(), p);
        String draft = dealService.generateCreative(ownerOpt.get(), p);
        deal = dealService.attachCreative(deal, draft);
        String text = "✍️ <b>Черновик для @" + TgHtml.esc(p.getTargetUsername()) + "</b>\n"
                + "Сделка #" + deal.getId() + "\n\n"
                + TgHtml.fromMarkdown(draft)
                + "\n\nОформите сделку, чтобы выбрать формат и отправить бриф админу.";
        editOrSend(chatId, messageId, text, keyboards.adDealCardInline(deal));
    }

    public void bookInterest(long chatId, int messageId, UserEntity user, long placementId) {
        adDealHandler.openDeal(chatId, messageId, user, placementId);
    }

    /** Ручная проверка — вход из «⋯ Ещё». */
    public void promptAddPlace(long chatId, UserEntity user) {
        sessionService.getOrCreate(chatId).setState(BotState.AD_RADAR_PLACE_INPUT);
        messageSender.sendTextWithInlineSafe(chatId,
                "🔎 <b>Проверить площадку</b>\n\nПришлите @username публичного канала.",
                keyboards.adRadarMenuInline());
    }

    public void handlePlaceInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        messageSender.sendTextSafe(chatId, "⏳ Проверяю…");
        Long ownerChannelId = userService.findActiveChannel(user).map(ChannelEntity::getId).orElse(null);
        try {
            AdPlacementEntity saved = adRadarService.checkAndSavePlacement(user, ownerChannelId, raw);
            showPlacementCard(chatId, 0, user, saved.getId());
        } catch (IllegalStateException ex) {
            messageSender.sendTextSafe(chatId, "⚠️ " + ex.getMessage());
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "Не удалось проверить. Нужен публичный @username.");
        }
    }

    public void handleWatchInput(long chatId, UserEntity user, String raw) {
        UserSession session = sessionService.getOrCreate(chatId);
        session.setState(BotState.MAIN_MENU);
        Long ownerChannelId = userService.findActiveChannel(user).map(ChannelEntity::getId).orElse(null);
        try {
            AdWatchSourceEntity saved = adRadarService.addWatchSource(user, ownerChannelId, raw);
            messageSender.sendTextWithInlineSafe(chatId,
                    "✅ Источник сигналов: <b>" + TgHtml.esc(saved.getLinkOrUsername()) + "</b>",
                    keyboards.adRadarMenuInline());
        } catch (Exception ex) {
            messageSender.sendTextSafe(chatId, "Не удалось добавить.");
        }
    }

    public void promptAddWatch(long chatId, UserEntity user) {
        sessionService.getOrCreate(chatId).setState(BotState.AD_RADAR_WATCH_INPUT);
        messageSender.sendTextWithInlineSafe(chatId,
                "Пришлите ссылку на чат, где кидают прайсы (разведка).",
                keyboards.adRadarMenuInline());
    }

    public void showWatches(long chatId, int messageId, UserEntity user) {
        List<AdWatchSourceEntity> watches = adRadarService.activeWatches(user.getId());
        String text = watches.isEmpty()
                ? "Чатов-источников пока нет."
                : "Источников: " + watches.size();
        editOrSend(chatId, messageId, text, keyboards.adRadarMenuInline());
    }

    public void showPlacements(long chatId, int messageId, UserEntity user) {
        List<AdPlacementEntity> places = adRadarService.recentPlacements(user.getId());
        if (places.isEmpty()) {
            editOrSend(chatId, messageId, "Пока пусто — нажмите «Начать поиск».", keyboards.adRadarMenuInline());
            return;
        }
        showMatchFromList(chatId, messageId, places);
    }

    private void showMatchFromList(long chatId, int messageId, List<AdPlacementEntity> places) {
        StringBuilder sb = new StringBuilder("📡 <b>Недавние площадки</b>\n\n");
        for (AdPlacementEntity p : places) {
            sb.append("• <b>@").append(TgHtml.esc(p.getTargetUsername())).append("</b>\n");
        }
        editOrSend(chatId, messageId, sb.toString().trim(), keyboards.adRadarMatchInline(places));
    }

    public void showHits(long chatId, int messageId, UserEntity user) {
        List<AdRadarHitEntity> hits = hitRepository.findTop10ByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "NEW");
        editOrSend(chatId, messageId,
                hits.isEmpty() ? "Сигналов пока нет." : "Сигналов: " + hits.size(),
                keyboards.adRadarMenuInline());
    }

    public void recheck(long chatId, int messageId, UserEntity user, long placementId) {
        messageSender.editTextSafe(chatId, messageId, "⏳ Обновляю…");
        adRadarService.recheckPlacement(user.getId(), placementId).ifPresentOrElse(
                saved -> showPlacementCard(chatId, messageId, user, saved.getId()),
                () -> messageSender.sendTextSafe(chatId, "Площадка не найдена."));
    }

    public void handle(long chatId, int messageId, String callbackData, UserEntity user) {
        if (callbackData.equals(CallbackData.AGENT_RADAR)) {
            showMenu(chatId, messageId, user);
        } else if (callbackData.equals(CallbackData.AGENT_RADAR_MATCH)) {
            showMatch(chatId, messageId, user);
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
        } else if (callbackData.startsWith(CallbackData.AGENT_RADAR_VIEW)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_RADAR_VIEW.length()));
            showPlacementCard(chatId, messageId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_RADAR_CREATIVE)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_RADAR_CREATIVE.length()));
            generateCreative(chatId, messageId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_RADAR_BOOK)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_RADAR_BOOK.length()));
            bookInterest(chatId, messageId, user, id);
        } else if (callbackData.startsWith(CallbackData.AGENT_RADAR_RECHECK)) {
            long id = Long.parseLong(callbackData.substring(CallbackData.AGENT_RADAR_RECHECK.length()));
            recheck(chatId, messageId, user, id);
        } else {
            showMenu(chatId, messageId, user);
        }
    }

    private static String formatNum(int n) {
        return String.format("%,d", n).replace(',', ' ');
    }

    private static String shorten(String s, int max) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private void editOrSend(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            messageSender.editTextOrReplace(chatId, messageId, text, keyboard);
        } else {
            messageSender.sendTextWithInlineSafe(chatId, text, keyboard);
        }
    }
}
