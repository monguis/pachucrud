package com.pachuco.pachucrud.service;

import java.math.BigDecimal;
import java.util.UUID;
import com.pachuco.pachucrud.model.ThrowModel;

public interface BetService {
    void placeBet(UUID gameId, UUID playerId, BigDecimal amount);
    String settleBet(UUID gameId, UUID playerId, ThrowModel houseModel, ThrowModel playerModel);
}
