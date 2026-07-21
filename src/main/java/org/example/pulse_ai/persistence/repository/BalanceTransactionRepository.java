package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.BalanceTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransactionEntity, Long> {
}
