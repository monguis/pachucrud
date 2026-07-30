package com.pachuco.pachucrud.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface BetService {
    void placeBet(UUID gameId, UUID playerId, BigDecimal amount);
    void settleBet(UUID gameId, UUID playerId, int houseRoll, int playerRoll);
}
