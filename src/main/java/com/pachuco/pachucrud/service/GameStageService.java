package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.service.model.GameState;
import java.util.List;
import java.util.UUID;

public interface GameStageService {
    GameState advanceToEnoughPlayers(UUID gameId);
    GameState advanceToGameStart(UUID gameId, List<UUID> playerOrder);
    GameState advanceToPlayersBetSetting(UUID gameId);
    GameState advanceToBankThrow(UUID gameId);
    GameState advanceToRoundCompleted(UUID gameId);
    GameState advanceToNextRound(UUID gameId);
    GameState markPlayerReady(UUID gameId, UUID playerId);
    GameState getCurrentTurn(UUID gameId);
}
