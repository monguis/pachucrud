package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.model.RollResult;
import java.util.UUID;

public interface RollService {
    ThrowModel roll5D6();
    RollResult houseRoll(UUID gameId, UUID housePlayerId);
    RollResult playerRoll(UUID gameId, UUID playerId);
}
