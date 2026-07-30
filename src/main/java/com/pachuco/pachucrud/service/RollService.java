package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.model.GameState;
import java.util.UUID;

public interface RollService {
    int rollD6();
    GameState houseRoll(UUID gameId, UUID housePlayerId);
    GameState playerRoll(UUID gameId, UUID playerId);
    ThrowModel roll5D6();
}
