package org.example.pulse_ai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.DependsOn("h2LocalSchemaPatcher")
public class PackageSeeder {

    private final PackageRepository packageRepository;

    @PostConstruct
    void seed() {
        // Каталог всегда синхронизирует PackageCatalogSync; сидер — no-op страховка.
        if (packageRepository.count() == 0) {
            log.info("Package catalog empty at seed time — PackageCatalogSync will upsert");
        }
    }
}
