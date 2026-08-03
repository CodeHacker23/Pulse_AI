package org.example.pulse_ai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.repository.ScoutAccountRepository;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.DependsOn("h2LocalSchemaPatcher")
public class ScoutAccountSeeder {

    private final ScoutAccountRepository accountRepository;

    @PostConstruct
    void seed() {
        if (accountRepository.count() == 0) {
            // Парсеры (join/parse/vacuum) — без ЛС. Sender'ы добавляешь отдельно (лимит 30–40).
            accountRepository.save(account(1, "parser-1", "PARSER", 0));
            accountRepository.save(account(2, "parser-2", "OBSERVER", 0));
            log.info("Seeded scout_accounts: #1 PARSER, #2 OBSERVER. SENDER IDs start at 100.");
            return;
        }
        // Align legacy: текущие CZ-фейки → PARSER/OBSERVER (без ЛС); sender'ы — отдельно
        for (ScoutAccountEntity a : accountRepository.findAll()) {
            String label = a.getLabel() != null ? a.getLabel() : "";
            if (label.contains("outreach-1") || label.equals("outreach-1")) {
                // раньше был sender — по новой схеме это парсер, пока нет отдельных OUTREACH
                if ("OUTREACH".equals(a.getAccountType())) {
                    a.setAccountType("PARSER");
                    a.setDailyLimit(0);
                    accountRepository.save(a);
                    log.info("Reclassified {} → PARSER (DM only via dedicated SENDER accounts)", label);
                }
            }
            if ("OUTREACH".equals(a.getAccountType()) || "SENDER".equals(a.getAccountType())) {
                if (a.getDailyLimit() > 0 && a.getDailyLimit() < 30) {
                    a.setDailyLimit(35);
                    accountRepository.save(a);
                }
            }
            if (("OBSERVER".equals(a.getAccountType()) || "PARSER".equals(a.getAccountType()))
                    && a.getDailyLimit() != 0) {
                a.setDailyLimit(0);
                accountRepository.save(a);
            }
        }
    }

    private static ScoutAccountEntity account(long id, String label, String type, int dailyLimit) {
        ScoutAccountEntity entity = new ScoutAccountEntity();
        entity.setId(id);
        entity.setLabel(label);
        entity.setAccountType(type);
        entity.setStatus("ACTIVE");
        entity.setDailyLimit(dailyLimit);
        return entity;
    }
}
