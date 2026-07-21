package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.UserEntitlementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserEntitlementRepository extends JpaRepository<UserEntitlementEntity, Long> {

    @Query("""
            SELECT e FROM UserEntitlementEntity e
            WHERE e.userId = :userId AND e.perkCode = :perkCode
              AND (e.expiresAt IS NULL OR e.expiresAt > :now)
              AND (e.usesRemaining IS NULL OR e.usesRemaining > 0)
            ORDER BY e.grantedAt DESC
            """)
    List<UserEntitlementEntity> findActive(
            @Param("userId") Long userId,
            @Param("perkCode") String perkCode,
            @Param("now") Instant now
    );

    List<UserEntitlementEntity> findByUserIdAndPerkCodeOrderByGrantedAtDesc(Long userId, String perkCode);

    @Query("""
            SELECT e FROM UserEntitlementEntity e
            WHERE e.perkCode = :perkCode
              AND (e.expiresAt IS NULL OR e.expiresAt > :now)
              AND (e.usesRemaining IS NULL OR e.usesRemaining > 0)
            """)
    List<UserEntitlementEntity> findAllActiveByPerkCode(
            @Param("perkCode") String perkCode,
            @Param("now") Instant now
    );
}
