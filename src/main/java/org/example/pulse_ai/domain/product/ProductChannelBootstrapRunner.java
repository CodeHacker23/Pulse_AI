package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.PulseProductChannelProperties;
import org.example.pulse_ai.keyboard.KeyboardFactory;
import org.example.pulse_ai.telegram.TelegramMessageSender;
import org.example.pulse_ai.text.TgHtml;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class ProductChannelBootstrapRunner {

    private final PulseProductChannelProperties properties;
    private final ProductChannelService productChannelService;
    private final TelegramMessageSender messageSender;
    private final KeyboardFactory keyboards;

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void bootstrapProductChannel() {
        if (!properties.isEnabled() || !properties.isBootstrapWelcomeOnStart()) {
            return;
        }
        try {
            ProductChannelService.PublishOutcome outcome = productChannelService.publishBootstrapWelcome();
            if (!outcome.success()) {
                log.warn("Product channel bootstrap skipped: {}", outcome.error());
                return;
            }
            log.info("Product channel welcome post published: {}", outcome.link());
            notifyOwners(outcome.link());
        } catch (Exception ex) {
            log.warn("Product channel bootstrap failed (bot continues): {}", ex.getMessage());
        }
    }

    private void notifyOwners(String postLink) {
        if (properties.getOwnerTelegramIds().isEmpty()) {
            return;
        }
        String text = """
                ✅ <b>Первый пост в канале опубликован</b>

                %s

                Откройте /product — генерируйте идеи, правьте и публикуйте дальше.""".formatted(
                postLink != null
                        ? "🔗 <a href=\"" + TgHtml.esc(postLink) + "\">Открыть пост</a>"
                        : "Пост в канале Pulse AI на месте.");

        for (Long ownerId : properties.getOwnerTelegramIds()) {
            messageSender.sendTextWithInlineSafe(ownerId, text, keyboards.productMenuInline());
        }
    }
}
