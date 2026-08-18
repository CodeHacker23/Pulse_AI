package org.example.pulse_ai.persistence.repository;

import org.example.pulse_ai.persistence.entity.ScoutMessageArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScoutMessageArchiveRepository extends JpaRepository<ScoutMessageArchiveEntity, Long> {

    Optional<ScoutMessageArchiveEntity> findByScoutAccountIdAndPeerIdAndTgMessageId(
            Long scoutAccountId, String peerId, Long tgMessageId);

    List<ScoutMessageArchiveEntity> findTop80ByScoutAccountIdAndPeerIdOrderByMessageAtAscTgMessageIdAsc(
            Long scoutAccountId, String peerId);

    List<ScoutMessageArchiveEntity> findTop80ByScoutAccountIdAndPeerUsernameIgnoreCaseOrderByMessageAtAscTgMessageIdAsc(
            Long scoutAccountId, String peerUsername);

    List<ScoutMessageArchiveEntity> findTop400ByScoutAccountIdOrderByMessageAtDesc(Long scoutAccountId);
}
