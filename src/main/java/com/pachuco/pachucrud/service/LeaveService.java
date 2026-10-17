package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.service.model.GameState;
import java.util.UUID;

public interface LeaveService {
    /** The player gives up their seat. Leaving a game you are not seated in changes nothing. */
    GameState leaveGame(UUID gameId, UUID playerId);
}
