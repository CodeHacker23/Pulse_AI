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
public class PackageSeeder {

    private final PackageRepository packageRepository;

    @PostConstruct
    void seed() {
        if (packageRepository.count() > 0) {
            return;
        }
        packageRepository.save(pack("START", "Старт", 10, 890, 450, (short) 1, (short) 1, false));
        packageRepository.save(pack("CONTENT", "Оптимал", 18, 1490, 750, (short) 2, (short) 2, false));
        packageRepository.save(pack("PRO", "Про", 30, 1990, 1000, (short) 3, (short) 3, true));
        log.info("Seeded default payment packages");
    }

    private static PackageEntity pack(
            String code, String name, int requests, int rub, int stars, short sort,
            short perkChoices, boolean priority
    ) {
        PackageEntity entity = new PackageEntity();
        entity.setCode(code);
        entity.setName(name);
        entity.setRequestCount(requests);
        entity.setPriceRub(rub);
        entity.setStarsAmount(stars);
        entity.setActive(true);
        entity.setSortOrder(sort);
        entity.setPerkChoicesCount(perkChoices);
        entity.setIncludesPriority(priority);
        return entity;
    }
}
