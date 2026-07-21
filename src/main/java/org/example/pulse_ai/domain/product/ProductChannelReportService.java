package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.example.pulse_ai.persistence.repository.ProductChannelPostRepository;
import org.example.pulse_ai.stats.scraper.TelegramPublicChannelScraper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProductChannelReportService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"));

    private final PulseProductChannelProperties properties;
    private final ProductChannelPostRepository postRepository;
    private final TelegramPublicChannelScraper scraper;
    private final ProductStyleLearnerService styleLearner;

    @Transactional(readOnly = true)
    public String buildOwnerReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Отчёт канала продукта</b>\n\n");

        if (properties.getChannelUsername() != null && !properties.getChannelUsername().isBlank()) {
            var data = scraper.fetchChannelData(properties.getChannelUsername(), 15, 2, 15_000);
            sb.append("📢 @").append(properties.getChannelUsername().replace("@", ""));
            if (data.subscriberCount() != null) {
                sb.append(" · 👥 ").append(data.subscriberCount());
            }
            sb.append("\n📝 Постов в выборке: ").append(data.posts().size()).append("\n\n");
        } else if (properties.getChannelChatId() != null) {
            sb.append("📢 Chat ID: <code>").append(properties.getChannelChatId()).append("</code>\n\n");
        }

        long published = postRepository.count();
        List<ProductChannelPostEntity> recent = postRepository.findTop10ByOrderByCreatedAtDesc();
        sb.append("🤖 Черновиков/публикаций через бота: <b>").append(published).append("</b>\n");
        if (!recent.isEmpty()) {
            sb.append("\n<b>Последние:</b>\n");
            for (ProductChannelPostEntity p : recent.stream().limit(5).toList()) {
                sb.append("• ").append(p.getRubric().label());
                sb.append(" — ").append(p.getStatus().name().toLowerCase());
                if (p.getPublishedAt() != null) {
                    sb.append(" (").append(DT.format(p.getPublishedAt().atZone(MSK))).append(')');
                }
                sb.append('\n');
            }
        }

        sb.append("\n<b>Референсы для стиля:</b> ");
        if (properties.getReferenceChannels() == null || properties.getReferenceChannels().isEmpty()) {
            sb.append("<i>не настроены</i>");
        } else {
            sb.append(String.join(", ", properties.getReferenceChannels()));
        }

        sb.append("\n\n<i>Обновить стиль: кнопка «🔄 Учиться с каналов» в /product</i>");
        return sb.toString().trim();
    }
}
