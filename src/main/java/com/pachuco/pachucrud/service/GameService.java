package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameService {
    GameEntity createGame(UUID creatorId);
    void joinGame(UUID gameId, UUID userId);
    void startRound(UUID gameId, UUID housePlayerId, BigDecimal betLimit);
    List<GameEntity> getAllGames();
    Optional<GameState> getGameState(UUID gameId);
    void completeGame(UUID gameId);
    void deleteGame(UUID gameId);
}
