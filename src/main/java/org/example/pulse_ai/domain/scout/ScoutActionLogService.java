package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ScoutActionLogEntity;
import org.example.pulse_ai.persistence.repository.ScoutActionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScoutActionLogService {

    private final ScoutActionLogRepository repository;

    @Transactional
    public void log(Long scoutAccountId, Long userId, String action, String status, String payload, String error) {
        ScoutActionLogEntity entity = new ScoutActionLogEntity();
        entity.setScoutAccountId(scoutAccountId);
        entity.setUserId(userId);
        entity.setAction(action);
        entity.setStatus(status != null ? status : "OK");
        if (payload != null) {
            entity.setPayload(payload.length() > 1000 ? payload.substring(0, 1000) : payload);
        }
        if (error != null) {
            entity.setErrorText(error.length() > 500 ? error.substring(0, 500) : error);
        }
        repository.save(entity);
    }

    public void ok(Long scoutAccountId, Long userId, String action, String payload) {
        log(scoutAccountId, userId, action, "OK", payload, null);
    }

    public void fail(Long scoutAccountId, Long userId, String action, String payload, String error) {
        log(scoutAccountId, userId, action, "FAIL", payload, error);
    }

    public List<ScoutActionLogEntity> recent() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }
}
