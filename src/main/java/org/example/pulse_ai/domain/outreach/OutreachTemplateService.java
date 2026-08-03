package org.example.pulse_ai.domain.outreach;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.pulse_ai.persistence.entity.OutreachMessageTemplateEntity;
import org.example.pulse_ai.persistence.repository.OutreachMessageTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@org.springframework.context.annotation.DependsOn("h2LocalSchemaPatcher")
public class OutreachTemplateService {

    private final OutreachMessageTemplateRepository repository;

    @PostConstruct
    void seedDefaults() {
        if (!repository.findByUserIdIsNullAndActiveTrueOrderByIdAsc().isEmpty()) {
            return;
        }
        saveGlobal("INVITE", "Invite default",
                "Привет, {username}! Веду канал {channel} — там про {topic}. Буду рад, если заглянете.");
        saveGlobal("CUSTDEV", "Custdev default",
                "Здравствуйте, {username}! Короткий опрос (2–3 вопроса) по продукту — удобно ответить?");
        saveGlobal("OFFER", "Offer default",
                "Привет, {username}! Есть решение под {topic} — рассказать в двух словах?");
        log.info("Seeded default outreach message templates");
    }

    public List<OutreachMessageTemplateEntity> listAll() {
        return repository.findTop20ByOrderByIdDesc();
    }

    public List<OutreachMessageTemplateEntity> forUser(Long userId) {
        List<OutreachMessageTemplateEntity> own = repository.findByUserIdAndActiveTrueOrderByIdAsc(userId);
        if (!own.isEmpty()) {
            return own;
        }
        return repository.findByUserIdIsNullAndActiveTrueOrderByIdAsc();
    }

    @Transactional
    public OutreachMessageTemplateEntity saveGlobal(String scenario, String name, String body) {
        OutreachMessageTemplateEntity e = new OutreachMessageTemplateEntity();
        e.setScenario(scenario);
        e.setName(name);
        e.setBody(body);
        e.setActive(true);
        return repository.save(e);
    }

    @Transactional
    public OutreachMessageTemplateEntity upsert(Long id, Long userId, String scenario, String name, String body) {
        OutreachMessageTemplateEntity e = id != null
                ? repository.findById(id).orElseGet(OutreachMessageTemplateEntity::new)
                : new OutreachMessageTemplateEntity();
        e.setUserId(userId);
        e.setScenario(scenario != null ? scenario : "INVITE");
        e.setName(name != null ? name : "template");
        e.setBody(body);
        e.setActive(true);
        return repository.save(e);
    }

    public String resolveBody(Long userId, String scenario, String fallback) {
        return forUser(userId).stream()
                .filter(t -> scenario == null || scenario.equalsIgnoreCase(t.getScenario()))
                .map(OutreachMessageTemplateEntity::getBody)
                .findFirst()
                .orElse(fallback);
    }
}
