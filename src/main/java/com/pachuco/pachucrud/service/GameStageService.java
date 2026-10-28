package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.service.model.GameState;
import java.util.List;
import java.util.UUID;

public interface GameStageService {
    GameState advanceToEnoughPlayers(UUID gameId);
    GameState advanceToGameStart(UUID gameId, List<UUID> playerOrder);
    GameState advanceToPlayersBetSetting(UUID gameId);
    GameState advanceToDiceRolling(UUID gameId);
    GameState passTurn(UUID gameId, UUID playerId);
    GameState advanceToRoundCompleted(UUID gameId);
    GameState advanceToNextRound(UUID gameId);
    GameState markPlayerReady(UUID gameId, UUID playerId);
    GameState getCurrentTurn(UUID gameId);
    GameState failLobby(UUID gameId);
}
