package org.example.pulse_ai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.PackageEntity;
import org.example.pulse_ai.persistence.repository.PackageRepository;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.DependsOn("h2LocalSchemaPatcher")
public class PackageCatalogSync {

    private final PackageRepository packageRepository;

    @PostConstruct
    void sync() {
        upsert("START", "Старт", 10, 890, 450, (short) 1, (short) 1, false);
        upsert("CONTENT", "Оптимал", 18, 1490, 750, (short) 2, (short) 2, false);
        upsert("PRO", "Про", 30, 1990, 1000, (short) 3, (short) 3, true);
    }

    private void upsert(
            String code,
            String name,
            int requests,
            int rub,
            int stars,
            short sort,
            short perkChoices,
            boolean priority
    ) {
        PackageEntity pack = packageRepository.findAll().stream()
                .filter(p -> code.equals(p.getCode()))
                .findFirst()
                .orElseGet(() -> {
                    PackageEntity entity = new PackageEntity();
                    entity.setCode(code);
                    return entity;
                });
        pack.setName(name);
        pack.setRequestCount(requests);
        pack.setPriceRub(rub);
        pack.setStarsAmount(stars);
        pack.setSortOrder(sort);
        pack.setActive(true);
        pack.setPerkChoicesCount(perkChoices);
        pack.setIncludesPriority(priority);
        packageRepository.save(pack);
        log.debug("Synced package {}", code);
    }
}
