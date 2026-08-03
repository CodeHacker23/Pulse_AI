package org.example.pulse_ai.domain.radar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.ai.LlmService;
import org.example.pulse_ai.persistence.entity.AdDealEntity;
import org.example.pulse_ai.persistence.entity.AdPlacementEntity;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.UserEntity;
import org.example.pulse_ai.persistence.repository.AdDealRepository;
import org.example.pulse_ai.persistence.repository.AdPlacementRepository;
import org.example.pulse_ai.text.TextHumanizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdDealService {

    public static final short COMMISSION_PERCENT = 20;

    private static final String CREATIVE_SYSTEM = """
            Ты пишешь рекламный пост для размещения в чужом Telegram-канале.
            Цель — привести подписчиков/покупателей на канал или оффер рекламодателя.
            Без коуч-штампов, без «5 советов», без кавычек-ёлочек и длинных тире.
            Структура: цепляющий заголовок в **жирном**, короткие абзацы, 1 CTA со ссылкой/упоминанием.
            Длина 350–700 символов. Только текст поста, без пояснений.""";

    private final AdDealRepository dealRepository;
    private final AdPlacementRepository placementRepository;
    private final LlmService llmService;

    public static int clientPrice(int adminPriceRub) {
        return Math.round(adminPriceRub * (100 + COMMISSION_PERCENT) / 100f);
    }

    @Transactional(readOnly = true)
    public List<AdDealEntity> listForUser(long userId) {
        return dealRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<AdDealEntity> findOwned(long dealId, long userId) {
        return dealRepository.findByIdAndUserId(dealId, userId);
    }

    /**
     * Открывает или продолжает сделку по площадке.
     * Базовая цена — оценка с карточки площадки; формат выбирается отдельно.
     */
    @Transactional
    public AdDealEntity openOrGet(UserEntity user, ChannelEntity owner, AdPlacementEntity placement) {
        Optional<AdDealEntity> open = dealRepository
                .findTop10ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(d -> placement.getId().equals(d.getPlacementId())
                        && AdDealStatuses.isOpen(d.getStatus()))
                .findFirst();
        if (open.isPresent()) {
            return open.get();
        }
        int base = AdRadarService.estimatePostPrice(null, placement.getAvgViews());
        Integer fromNotes = parsePriceFromNotes(placement.getQualityNotes());
        if (fromNotes != null) {
            base = fromNotes;
        }
        AdDealEntity deal = new AdDealEntity();
        deal.setUserId(user.getId());
        deal.setOwnerChannelId(owner != null ? owner.getId() : null);
        deal.setPlacementId(placement.getId());
        deal.setTargetUsername(placement.getTargetUsername());
        deal.setStatus(AdDealStatuses.INTEREST);
        deal.setPinFormat(null);
        deal.setCommissionPercent(COMMISSION_PERCENT);
        applyPrices(deal, base);
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity setFormat(AdDealEntity deal, String formatCode) {
        String fmt = formatCode != null ? formatCode : AdPinFormats.POST;
        int base = baseAdminWithoutFormat(deal);
        deal.setPinFormat(fmt);
        applyPrices(deal, Math.round(base * AdPinFormats.priceMultiplier(fmt)));
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity attachCreative(AdDealEntity deal, String draft) {
        deal.setCreativeDraft(draft);
        if (AdDealStatuses.INTEREST.equals(deal.getStatus())
                || deal.getStatus() == null) {
            deal.setStatus(AdDealStatuses.BRIEF);
        }
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity markSentToAdmin(AdDealEntity deal) {
        deal.setStatus(AdDealStatuses.AWAITING_ADMIN);
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity markAgreed(AdDealEntity deal, Integer confirmedAdminPrice) {
        if (confirmedAdminPrice != null && confirmedAdminPrice > 0) {
            applyPrices(deal, confirmedAdminPrice);
        }
        deal.setStatus(AdDealStatuses.AGREED);
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity markRejected(AdDealEntity deal, String reason) {
        deal.setStatus(AdDealStatuses.REJECTED);
        if (reason != null && !reason.isBlank()) {
            deal.setAdminNotes(reason.trim());
        }
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity setAdminPrice(AdDealEntity deal, int adminPriceRub) {
        applyPrices(deal, adminPriceRub);
        return dealRepository.save(deal);
    }

    @Transactional
    public AdDealEntity appendAdminNotes(AdDealEntity deal, String note) {
        String prev = deal.getAdminNotes();
        String next = note.trim();
        deal.setAdminNotes(prev == null || prev.isBlank() ? next : prev + "\n---\n" + next);
        return dealRepository.save(deal);
    }

    /** Текст для копирования админу площадки. */
    public String buildAdminBrief(AdDealEntity deal, ChannelEntity owner, AdPlacementEntity placement) {
        String format = AdPinFormats.label(deal.getPinFormat());
        int admin = deal.getPriceAdminRub() != null ? deal.getPriceAdminRub() : 0;
        String product = owner != null && owner.getSalesFaq() != null && !owner.getSalesFaq().isBlank()
                ? shorten(owner.getSalesFaq(), 280)
                : (owner != null ? owner.getTitle() : "наш канал");
        String advertiser = owner != null
                ? (owner.getUsername() != null
                ? "«" + owner.getTitle() + "» @" + owner.getUsername()
                : "«" + owner.getTitle() + "»")
                : "рекламодатель";
        String creative = deal.getCreativeDraft() != null && !deal.getCreativeDraft().isBlank()
                ? deal.getCreativeDraft().trim()
                : "(креатив приложим отдельно)";

        return """
                Здравствуйте! Интересует размещение в @%s.

                Формат: %s
                Ориентир по бюджету: до %d ₽ (готовы обсудить ваш прайс)
                Рекламодатель: %s
                Оффер / продукт: %s

                --- креатив ---
                %s
                ---

                Подскажите, пожалуйста:
                1) актуальная цена на этот формат
                2) ближайший свободный слот
                3) ограничения по тексту/ссылкам

                Спасибо!""".formatted(
                deal.getTargetUsername(),
                format,
                admin > 0 ? admin : 1500,
                advertiser,
                product,
                creative
        ).trim();
    }

    public String generateCreative(ChannelEntity owner, AdPlacementEntity placement) {
        String product = blank(owner.getSalesFaq(), "опишите продукт в профиле ассистента (FAQ)");
        String style = blank(owner.getContentStylePrompt(), "тон канала рекламодателя");
        String objections = blank(owner.getSalesObjections(), "—");
        String userPrompt = """
                Рекламодатель: «%s» (@%s)
                Продукт / оффер:
                %s

                Стиль (приоритет):
                %s

                Возражения/формулировки:
                %s

                Площадка размещения: @%s (%s)
                Охват площадки ~%s просмотров, доля рекламы ~%s%%.

                Напиши один рекламный пост под эту площадку. CTA ведёт на канал/оффер рекламодателя.
                """.formatted(
                owner.getTitle(),
                blank(owner.getUsername(), "channel"),
                product,
                style,
                objections,
                placement.getTargetUsername(),
                blank(placement.getTargetTitle(), placement.getTargetUsername()),
                placement.getAvgViews() != null ? placement.getAvgViews() : "н/д",
                placement.getAdRatioPercent() != null ? placement.getAdRatioPercent() : 0
        );
        try {
            String text = llmService.completeTextWithTimeout(CREATIVE_SYSTEM, userPrompt, 45, 1200);
            return text != null ? TextHumanizer.humanize(text.trim()) : fallbackCreative(owner);
        } catch (Exception ex) {
            log.warn("Ad creative failed: {}", ex.getMessage());
            return fallbackCreative(owner);
        }
    }

    @Transactional(readOnly = true)
    public Optional<AdPlacementEntity> placementOf(AdDealEntity deal) {
        if (deal.getPlacementId() == null) {
            return Optional.empty();
        }
        return placementRepository.findById(deal.getPlacementId());
    }

    private void applyPrices(AdDealEntity deal, int adminPriceRub) {
        int admin = Math.max(100, adminPriceRub);
        deal.setPriceAdminRub(admin);
        deal.setPriceClientRub(clientPrice(admin));
        deal.setCommissionPercent(COMMISSION_PERCENT);
    }

    private static int baseAdminWithoutFormat(AdDealEntity deal) {
        if (deal.getPriceAdminRub() == null) {
            return 1500;
        }
        float m = AdPinFormats.priceMultiplier(
                deal.getPinFormat() != null ? deal.getPinFormat() : AdPinFormats.POST);
        return Math.max(100, Math.round(deal.getPriceAdminRub() / m));
    }

    private static Integer parsePriceFromNotes(String notes) {
        if (notes == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("~(\\d+)\\s*₽").matcher(notes);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String fallbackCreative(ChannelEntity owner) {
        String link = owner.getUsername() != null ? "https://t.me/" + owner.getUsername() : "наш канал";
        return "**" + owner.getTitle() + "**\n\n"
                + "Коротко о нас — загляните: " + link + "\n\n"
                + "_Черновик-заглушка: дополните оффер и CTA._";
    }

    private static String blank(String v, String fallback) {
        return v != null && !v.isBlank() ? v.trim() : fallback;
    }

    private static String shorten(String s, int max) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
