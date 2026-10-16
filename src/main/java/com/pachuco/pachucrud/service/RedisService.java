package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface RedisService {
    boolean isReachable();
    void setBalance(UUID userId, BigDecimal balance);
    Optional<BigDecimal> getBalance(UUID userId);
    /** Atomically adds to a cached balance; leaves a missing key missing. */
    void incrementBalanceIfPresent(UUID userId, BigDecimal amount);
    void deleteBalance(UUID userId);
    void setGameState(UUID gameId, GameState state);
    Optional<GameState> getGameState(UUID gameId);
    void deleteGameState(UUID gameId);
    void setLastSequence(UUID gameId, int seq);
    Optional<Integer> getLastSequence(UUID gameId);
}
