package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "pulse.outreach")
public class PulseOutreachProperties {

    /** Реальная отправка через MTProto-аккаунты (scout). По умолчанию — только очередь в БД. */
    private boolean dispatchEnabled = false;

    /** Лимит исходящих ЛС в месяц на пользователя (тариф Ассистент). */
    private int monthlySendLimit = 100;

    /** Макс. новых ЛС в сутки на кампанию (sender-акки обычно 30–40). */
    private int defaultDailyLimit = 35;

    /** Пауза между отправками (сек) при dispatchEnabled. */
    private int minDelaySeconds = 45;

    private int maxDelaySeconds = 120;

    /** Макс. активных кампаний на пользователя. */
    private int maxCampaignsPerUser = 10;
}
