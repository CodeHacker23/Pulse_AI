package org.example.pulse_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private String token = "";
    private String username = "ChannelPulseBot";
    private boolean enabled;
    private int connectTimeoutMs = 120_000;
    private int socketTimeoutMs = 130_000;
    private int connectionRequestTimeoutMs = 30_000;
    private Proxy proxy = new Proxy();

    @Getter
    @Setter
    public static class Proxy {
        private boolean enabled;
        private String host = "127.0.0.1";
        private int port = 7890;
        private String type = "SOCKS5";
    }
}
