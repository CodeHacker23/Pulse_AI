package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.domain.product.ProductChannelPostStatus;
import org.example.pulse_ai.persistence.entity.ProductChannelPostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductChannelPostRepository extends JpaRepository<ProductChannelPostEntity, Long> {

    List<ProductChannelPostEntity> findTop10ByOrderByCreatedAtDesc();

    long countByStatus(ProductChannelPostStatus status);

    Optional<ProductChannelPostEntity> findFirstByCreatedByTelegramIdAndStatusOrderByPublishedAtDesc(
            Long createdByTelegramId, ProductChannelPostStatus status);
}
