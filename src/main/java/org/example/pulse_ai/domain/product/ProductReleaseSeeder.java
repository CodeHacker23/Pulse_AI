package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ProductReleaseEntity;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Если Flyway/seed не отработал (чистая H2) — заполняем реестр релизов.
 * Тексты — для канала: польза + интрига, без схем.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class ProductReleaseSeeder implements ApplicationRunner {

    private final ProductReleaseService releaseService;
    private final org.example.pulse_ai.persistence.repository.ProductReleaseRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        log.info("Seeding product_releases with known Pulse AI updates");
        seed("0.3.0", "Контент, который помнит", """
                ▪️План постов больше не предлагает одно и то же по кругу.
                ▪️Фото к посту — правка и тайминг в одном месте.
                ▪️Ассистент стал ближе: продажи, рост, настройки — без каши в меню.
                ▪️Ответы из переписки аккуратно попадают туда, где их ждут.
                """, "FEATURE");
        seed("0.3.1", "Рост без шума", """
                ▪️Появились «глаза» на площадках — видим сигналы раньше конкурентов.
                ▪️Исходящие касания — дозированно, без спама в лицо.
                ▪️Радар подкидывает идеи в контент-план, когда есть повод.
                ▪️Журнал действий — чтобы понимать, что сработало.
                """, "FEATURE");
        seed("0.4.0", "Стабильнее в эфире", """
                ▪️Сеть вокруг аккаунтов стала аккуратнее — меньше сюрпризов.
                ▪️Если что-то «придавило» — система сама пытается вырулить.
                ▪️Мёртвые узлы отсекаем, живые оставляем.
                """, "UPDATE");
        seed("0.4.1", "Пульт оператора", """
                ▪️Новая админ-консоль: видно статус, переписку, пулы — без рытья в логах.
                ▪️Залил список одним файлом — дальше само.
                ▪️Диалоги: ответил / отметил прочитанным — только когда ты решил.
                ▪️Парсинг аудитории умнее отсекает «пустые» аккаунты.
                """, "FEATURE");
        seed("0.4.2", "Апдейты как у большой игры", """
                ▪️Реестр релизов: Changelog собирается из фактов, не из фантазии.
                ▪️Патчноут в канал — коротко, с интригой, без «как мы это сделали».
                ▪️Добавил апдейт → собрал пост → в эфир.
                """, "FEATURE");
    }

    private void seed(String version, String title, String bullets, String category) {
        ProductReleaseEntity e = releaseService.upsert(version, title, bullets, category, "READY");
        log.info("Seeded release {}", e.getVersion());
    }
}
