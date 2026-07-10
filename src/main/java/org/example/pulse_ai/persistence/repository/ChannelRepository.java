package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChannelRepository extends JpaRepository<ChannelEntity, Long> {

    Optional<ChannelEntity> findByTelegramChatId(Long telegramChatId);

    Optional<ChannelEntity> findByOwnerUserIdAndConnectionStatus(
            Long ownerUserId,
            org.example.pulse_ai.domain.channel.ConnectionStatus connectionStatus
    );
}
