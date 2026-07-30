package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.repository.entity.TransactionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    List<TransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<TransactionEntity> findByEventId(UUID eventId);
}
