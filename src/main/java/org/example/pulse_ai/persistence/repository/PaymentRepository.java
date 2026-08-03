package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByExternalId(String externalId);

    @Query("""
            select case when count(p) > 0 then true else false end
            from PaymentEntity p, PackageEntity pkg
            where p.packageId = pkg.id
              and p.userId = :userId
              and p.status = org.example.pulse_ai.domain.payment.PaymentStatus.COMPLETED
              and pkg.code in ('CONTENT', 'PRO')
            """)
    boolean hasCompletedContentPlusPurchase(@Param("userId") long userId);
}
