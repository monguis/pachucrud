package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameRepository extends JpaRepository<GameEntity, UUID> {
    List<GameEntity> findByStatus(GameStatus status);
}
