package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.TransactionType;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.GameState.BetInfo;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BetServiceImpl implements BetService {

    private static final Logger log = LoggerFactory.getLogger(BetServiceImpl.class);

    private final EventService eventService;
    private final RedisService redisService;

    public BetServiceImpl(EventService eventService, RedisService redisService) {
        this.eventService = eventService;
        this.redisService = redisService;
    }

    @Override
    @Transactional
    public void placeBet(UUID gameId, UUID playerId, BigDecimal amount) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found in Redis"));

        if (!"BETTING".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not in betting phase");
        }

        if (playerId.equals(state.getHousePlayerId())) {
            throw new IllegalStateException("House player cannot place bets");
        }

        if (amount.compareTo(state.getBetLimit()) > 0) {
            throw new IllegalArgumentException("Bet amount exceeds limit of " + state.getBetLimit());
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Bet amount must be positive");
        }

        BigDecimal playerBalance = redisService.getBalance(playerId)
            .orElseThrow(() -> new IllegalStateException("Player balance not available"));

        if (playerBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance: have " + playerBalance + ", need " + amount);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("amount", amount);
        data.put("playerId", playerId.toString());

        eventService.writeEventWithTransaction(
            gameId, EventType.BET_PLACED, playerId, data,
            playerId, TransactionType.BET_HOLD, amount);

        redisService.setBalance(playerId, playerBalance.subtract(amount));
        state.getBets().add(new BetInfo(playerId, amount));
        redisService.setGameState(gameId, state);
    }

    @Override
    @Transactional
    public void settleBet(UUID gameId, UUID playerId, int houseRoll, int playerRoll) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found in Redis"));

        BetInfo bet = state.getBets().stream()
            .filter(b -> b.getPlayerId().equals(playerId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No bet found for player " + playerId));

        boolean playerWins = playerRoll > houseRoll;
        BigDecimal betAmount = bet.getAmount();
        BigDecimal outcomeAmount = playerWins ? betAmount : betAmount.negate();

        Map<String, Object> data = new HashMap<>();
        data.put("amount", betAmount);
        data.put("houseRoll", houseRoll);
        data.put("playerRoll", playerRoll);
        data.put("outcome", playerWins ? "win" : "loss");

        TransactionType txType = playerWins ? TransactionType.BET_WIN : TransactionType.BET_LOSS;
        eventService.writeEventWithTransaction(
            gameId, EventType.PLAYER_ROLLED, playerId, data,
            playerId, txType, betAmount);

        BigDecimal currentBalance = redisService.getBalance(playerId)
            .orElse(BigDecimal.ZERO);

        if (playerWins) {
            redisService.setBalance(playerId, currentBalance.add(betAmount.multiply(BigDecimal.valueOf(2))));
        }

        UUID houseId = state.getHousePlayerId();
        BigDecimal houseBalance = redisService.getBalance(houseId).orElse(BigDecimal.ZERO);
        if (playerWins) {
            redisService.setBalance(houseId, houseBalance.subtract(betAmount));
        } else {
            redisService.setBalance(houseId, houseBalance.add(betAmount));
        }

        state.getRolledPlayers().add(playerId);
        state.getPlayerRolls().add(new GameState.RollInfo(playerId, playerRoll));
        redisService.setGameState(gameId, state);
    }
}
