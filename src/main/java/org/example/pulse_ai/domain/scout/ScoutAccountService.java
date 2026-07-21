package org.example.pulse_ai.domain.scout;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.repository.ScoutAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScoutAccountService {

    private final ScoutAccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<ScoutAccountEntity> listAll() {
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ScoutAccountEntity> activeOutreach() {
        return accountRepository.findByAccountTypeAndStatusOrderByLastSentAtAscIdAsc("OUTREACH", "ACTIVE");
    }

    @Transactional(readOnly = true)
    public Optional<ScoutAccountEntity> pickOutreachAccount() {
        return activeOutreach().stream()
                .filter(a -> a.getSentToday() < a.getDailyLimit())
                .findFirst();
    }

    @Transactional
    public void recordSend(long accountId, boolean success, String error) {
        accountRepository.findById(accountId).ifPresent(account -> {
            if (success) {
                account.setSentToday(account.getSentToday() + 1);
                account.setLastSentAt(Instant.now());
                account.setLastError(null);
            } else {
                account.setLastError(error);
                if (error != null && (error.contains("FLOOD") || error.contains("BANNED"))) {
                    account.setStatus("FLOOD_WAIT");
                }
            }
            accountRepository.save(account);
        });
    }

    @Transactional
    public void resetDailyCounters() {
        for (ScoutAccountEntity account : accountRepository.findAll()) {
            account.setSentToday(0);
            accountRepository.save(account);
        }
    }
}
