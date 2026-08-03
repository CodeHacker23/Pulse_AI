package org.example.pulse_ai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.domain.payment.PackageKind;
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
        upsert("START", "Старт", 10, 890, 450, (short) 1, (short) 1, false,
                PackageKind.ANALYSIS, 0, 0, false);
        upsert("CONTENT", "Оптимал", 18, 1490, 750, (short) 2, (short) 2, false,
                PackageKind.ANALYSIS, 0, 0, false);
        upsert("PRO", "Про", 30, 1990, 1000, (short) 3, (short) 3, true,
                PackageKind.ANALYSIS, 0, 0, false);

        upsert("ASSIST", "Ассистент", 0, 3990, 2000, (short) 10, (short) 0, false,
                PackageKind.ASSISTANT, 100, 2, false);
        upsert("ASSIST_PLUS", "Ассистент+", 0, 6990, 3500, (short) 11, (short) 0, false,
                PackageKind.ASSISTANT, 500, 5, true);
        upsert("ASSIST_PRO", "Ассистент Pro", 0, 9990, 5000, (short) 12, (short) 0, true,
                PackageKind.ASSISTANT, 1000, 10, true);

        upsert("LS_100", "+100 ЛС", 0, 990, 500, (short) 20, (short) 0, false,
                PackageKind.LS_TOPUP, 100, 0, false);
        upsert("LS_500", "+500 ЛС", 0, 3490, 1750, (short) 21, (short) 0, false,
                PackageKind.LS_TOPUP, 500, 0, false);
        upsert("LS_1000", "+1000 ЛС", 0, 5990, 3000, (short) 22, (short) 0, false,
                PackageKind.LS_TOPUP, 1000, 0, false);
    }

    private void upsert(
            String code,
            String name,
            int requests,
            int rub,
            int stars,
            short sort,
            short perkChoices,
            boolean priority,
            PackageKind kind,
            int dmQuota,
            int parseQuota,
            boolean findAudience
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
        pack.setKind(kind.name());
        pack.setDmQuota(dmQuota);
        pack.setParseQuota(parseQuota);
        pack.setIncludesFindAudience(findAudience);
        packageRepository.save(pack);
        log.debug("Synced package {}", code);
    }
}
