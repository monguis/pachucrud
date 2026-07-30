package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.service.model.GameState;
import java.util.UUID;

public interface GameStageService {
    GameState advanceToEnoughPlayers(UUID gameId);
    GameState advanceToGameStart(UUID gameId);
    GameState advanceToNextRound(UUID gameId);
    GameState markPlayerReady(UUID gameId, UUID playerId);
    GameState getCurrentTurn(UUID gameId);
}
