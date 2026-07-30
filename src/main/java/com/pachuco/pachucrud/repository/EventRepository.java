package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.repository.entity.EventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends JpaRepository<EventEntity, UUID> {
    List<EventEntity> findByGameIdOrderBySequenceNumberAsc(UUID gameId);

    Optional<EventEntity> findTopByGameIdOrderBySequenceNumberDesc(UUID gameId);

    @Query("SELECT COALESCE(MAX(e.sequenceNumber), 0) FROM EventEntity e WHERE e.game.id = :gameId")
    Integer getMaxSequenceNumber(@Param("gameId") UUID gameId);

    List<EventEntity> findByGameIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
        UUID gameId, Integer afterSequence);

    List<EventEntity> findByActorIdOrderByCreatedAtAsc(UUID actorId);

    long countByEventTypeAndActorId(EventType eventType, UUID actorId);

    @Modifying
    @Query("DELETE FROM EventEntity e WHERE e.game.id = :gameId")
    void deleteByGameId(@Param("gameId") UUID gameId);
}
