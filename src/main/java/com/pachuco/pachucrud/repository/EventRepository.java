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

    /** Settlements in which the user was the House (recorded in the event's data). */
    @Query(value = "SELECT * FROM events WHERE event_type = 'PLAYER_ROLLED'"
        + " AND data->>'houseId' = :houseId ORDER BY created_at", nativeQuery = true)
    List<EventEntity> findHouseSettlements(@Param("houseId") String houseId);

    long countByEventTypeAndActorId(EventType eventType, UUID actorId);

    @Modifying
    @Query("DELETE FROM EventEntity e WHERE e.game.id = :gameId")
    void deleteByGameId(@Param("gameId") UUID gameId);
}
