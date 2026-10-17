package com.pachuco.pachucrud.service;

import java.math.BigDecimal;
import java.util.UUID;
import com.pachuco.pachucrud.model.ThrowModel;

public interface BetService {
    void placeBet(UUID gameId, UUID playerId, BigDecimal amount);
    String settleBet(UUID gameId, UUID playerId, ThrowModel houseModel, ThrowModel playerModel);
    /** Settles the Bet of a player who left before rolling as though they threw a Pachuco. */
    void forfeitBet(UUID gameId, UUID playerId);
    /** Refunds a Bet not yet settled, for a round that was abandoned. */
    void voidBet(UUID gameId, UUID playerId);
}
