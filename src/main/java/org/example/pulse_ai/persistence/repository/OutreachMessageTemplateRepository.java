package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.OutreachMessageTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutreachMessageTemplateRepository extends JpaRepository<OutreachMessageTemplateEntity, Long> {

    List<OutreachMessageTemplateEntity> findByUserIdIsNullAndActiveTrueOrderByIdAsc();

    List<OutreachMessageTemplateEntity> findByUserIdAndActiveTrueOrderByIdAsc(Long userId);

    List<OutreachMessageTemplateEntity> findTop20ByOrderByIdDesc();
}
