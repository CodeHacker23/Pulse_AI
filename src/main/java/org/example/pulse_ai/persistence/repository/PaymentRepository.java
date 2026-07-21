package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByExternalId(String externalId);
}
