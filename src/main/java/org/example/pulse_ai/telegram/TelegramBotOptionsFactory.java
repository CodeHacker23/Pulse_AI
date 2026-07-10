package org.example.pulse_ai.telegram;

import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.example.pulse_ai.config.TelegramBotProperties;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;

@Component
@RequiredArgsConstructor
public class TelegramBotOptionsFactory {

    private final TelegramBotProperties properties;

    public DefaultBotOptions create() {
        DefaultBotOptions options = new DefaultBotOptions();

        RequestConfig.Builder requestConfig = RequestConfig.custom()
                .setConnectTimeout(properties.getConnectTimeoutMs())
                .setSocketTimeout(properties.getSocketTimeoutMs())
                .setConnectionRequestTimeout(properties.getConnectionRequestTimeoutMs());

        TelegramBotProperties.Proxy proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled() && proxy.getHost() != null && !proxy.getHost().isBlank()) {
            if ("SOCKS5".equalsIgnoreCase(proxy.getType())) {
                options.setProxyHost(proxy.getHost());
                options.setProxyPort(proxy.getPort());
                options.setProxyType(DefaultBotOptions.ProxyType.SOCKS5);
            } else {
                options.setProxyType(DefaultBotOptions.ProxyType.HTTP);
                options.setProxyHost(proxy.getHost());
                options.setProxyPort(proxy.getPort());
                requestConfig.setProxy(new HttpHost(proxy.getHost(), proxy.getPort()));
            }
        } else {
            options.setProxyType(DefaultBotOptions.ProxyType.NO_PROXY);
        }

        options.setRequestConfig(requestConfig.build());
        return options;
    }
}
