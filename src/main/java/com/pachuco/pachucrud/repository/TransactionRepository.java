package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.repository.entity.TransactionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    List<TransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<TransactionEntity> findByEventId(UUID eventId);

    @Modifying
    @Query("DELETE FROM TransactionEntity t WHERE t.event.id IN (SELECT e.id FROM EventEntity e WHERE e.game.id = :gameId)")
    void deleteByGameId(@Param("gameId") UUID gameId);
}
