package org.example.pulse_ai.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.config.TelegramBotProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramConnectivityChecker {

    private static final URI TELEGRAM_API = URI.create("https://api.telegram.org");

    private final TelegramBotProperties properties;

    public void verifyOrWarn() {
        TelegramBotProperties.Proxy proxy = properties.getProxy();
        boolean proxyEnabled = proxy != null && proxy.isEnabled()
                && proxy.getHost() != null && !proxy.getHost().isBlank();

        if (proxyEnabled) {
            if (!isPortOpen(proxy.getHost(), proxy.getPort())) {
                log.error("""
                        Telegram proxy {}:{} недоступен.
                        Запустите VPN/прокси-клиент (Clash, v2rayN, Hiddify) и проверьте порт в application-local.yaml.""",
                        proxy.getHost(), proxy.getPort());
            }
        }

        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20));
            if (proxyEnabled && "SOCKS5".equalsIgnoreCase(proxy.getType())) {
                clientBuilder.proxy(java.net.ProxySelector.of(
                        new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            }
            HttpClient client = clientBuilder.build();
            HttpRequest request = HttpRequest.newBuilder(TELEGRAM_API)
                    .timeout(Duration.ofSeconds(25))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 500) {
                log.info("Связь с api.telegram.org OK (HTTP {}){}", response.statusCode(),
                        proxyEnabled ? ", через прокси " + proxy.getHost() + ":" + proxy.getPort() : "");
                return;
            }
            log.warn("api.telegram.org ответил HTTP {}", response.statusCode());
        } catch (Exception ex) {
            log.error("""
                    Нет стабильной связи с api.telegram.org: {}
                    
                    Что сделать:
                    1) Включите VPN на компьютере
                    2) В application-local.yaml включите прокси:
                       telegram.bot.proxy.enabled: true
                       telegram.bot.proxy.host: 127.0.0.1
                       telegram.bot.proxy.port: 7890   # Clash
                       telegram.bot.proxy.type: SOCKS5  # или HTTP
                    3) Перезапустите: .\\gradlew.bat bootRun
                    
                    Типичные порты: Clash 7890, v2rayN 10808, HTTP 8080""",
                    ex.getMessage());
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3_000);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
