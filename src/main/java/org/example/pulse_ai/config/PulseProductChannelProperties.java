package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.product")
public class PulseProductChannelProperties {

    private boolean enabled = false;

    /** @username канала продукта без @ */
    private String channelUsername = "";

    /** Telegram chat id канала (если известен; иначе резолвится по username) */
    private Long channelChatId;

    /** Telegram user id владельцев, кто может публиковать в канал продукта */
    private List<Long> ownerTelegramIds = new ArrayList<>();

    /** Публичные каналы, с которых бот учится стилю (username без @) */
    private List<String> referenceChannels = new ArrayList<>();

    /** Утренний черновик владельцу в ЛС */
    private boolean morningBriefEnabled = true;

    private int morningBriefHour = 9;

    /** Ссылка на бота для CTA в постах */
    private String botLink = "https://t.me/Pulsse_AI_bot";

    /** Локальная разработка: если owner-telegram-ids пуст — /product доступен всем */
    private boolean devOpenAccess = false;

    /** Опубликовать приветственный пост при старте, если в канале ещё нет публикаций */
    private boolean bootstrapWelcomeOnStart = true;

    public boolean isOwner(long telegramUserId) {
        if (ownerTelegramIds != null) {
            for (Long id : ownerTelegramIds) {
                if (id != null && id == telegramUserId) {
                    return true;
                }
            }
            // Список задан, но ID не совпал — для локалки можно открыть всем
            if (!ownerTelegramIds.isEmpty() && !devOpenAccess) {
                return false;
            }
        }
        return devOpenAccess || ownerTelegramIds == null || ownerTelegramIds.isEmpty();
    }
}
