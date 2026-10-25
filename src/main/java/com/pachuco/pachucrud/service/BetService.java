package com.pachuco.pachucrud.service;

import java.math.BigDecimal;
import java.util.UUID;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.model.GameState;

public interface BetService {
    void placeBet(UUID gameId, UUID playerId, BigDecimal amount);
    // String settleBet(UUID gameId, UUID playerId, ThrowModel houseModel, ThrowModel playerModel);
    /** Settles the Bet of a player who left before rolling as though they threw a Pachuco. */
    void forfeitBet(UUID gameId, UUID playerId);
    /** Hands back in full a Bet not yet settled, because its round was Abandoned. */
    void releaseBet(UUID gameId, UUID playerId);

    String settleBet_New(GameState gameId, UUID playerId, ThrowModel houseModel, ThrowModel playerModel);
}
