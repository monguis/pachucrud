package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.PachucoRules;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.model.TransactionType;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.GameState.BetInfo;
import com.pachuco.pachucrud.service.model.GameState.RollInfo;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
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

        if (!"PLAYERS_BET_SETTING".equals(state.getRoundStatus())) {
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

        BigDecimal playerBalance = redisService.getBalance(playerId).orElseGet(() -> {
            BigDecimal computed = eventService.computeUserBalance(playerId);
            redisService.setBalance(playerId, computed);
            return computed;
        });

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
    public String settleBet(UUID gameId, UUID playerId, ThrowModel houseModel, ThrowModel playerModel) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found in Redis"));

        BetInfo bet = state.getBets().stream()
            .filter(b -> b.getPlayerId().equals(playerId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No bet found for player " + playerId));

        BigDecimal betAmount = bet.getAmount();
        String outcome = resolveOutcome(houseModel, playerModel);

        Map<String, Object> data = new HashMap<>();
        data.put("amount", betAmount);
        data.put("playerId", playerId.toString());
        data.put("houseDice", houseModel.getDiceList());
        data.put("playerDice", playerModel.getDiceList());
        data.put("houseCombo", houseModel.getComboName());
        data.put("playerCombo", playerModel.getComboName());
        data.put("outcome", outcome);
        data.put("round", state.getCurrentRound());

        TransactionType txType = switch (outcome) {
            case "win" -> TransactionType.BET_WIN;
            case "win_double" -> TransactionType.BET_WIN_DOUBLE;
            case "lose_double" -> TransactionType.BET_LOSS_DOUBLE;
            default -> TransactionType.BET_LOSS;
        };

        BigDecimal netDelta = switch (outcome) {
            case "win" -> betAmount;
            case "win_double" -> betAmount.multiply(BigDecimal.valueOf(2));
            case "lose_double" -> betAmount.negate();
            default -> betAmount.negate();
        };

        eventService.writeEventWithTransaction(
            gameId, EventType.PLAYER_ROLLED, playerId, data,
            playerId, txType, netDelta, state.getCurrentRound());

        BigDecimal playerBalance = redisService.getBalance(playerId).orElseGet(() -> {
            BigDecimal computed = eventService.computeUserBalance(playerId);
            redisService.setBalance(playerId, computed);
            return computed;
        });
        BigDecimal houseBalance = redisService.getBalance(state.getHousePlayerId()).orElseGet(() -> {
            BigDecimal computed = eventService.computeUserBalance(state.getHousePlayerId());
            redisService.setBalance(state.getHousePlayerId(), computed);
            return computed;
        });

        switch (outcome) {
            case "win" -> {
                redisService.setBalance(playerId, playerBalance.add(betAmount.multiply(BigDecimal.valueOf(2))));
                redisService.setBalance(state.getHousePlayerId(), houseBalance.subtract(betAmount));
            }
            case "win_double" -> {
                redisService.setBalance(playerId, playerBalance.add(betAmount.multiply(BigDecimal.valueOf(3))));
                redisService.setBalance(state.getHousePlayerId(), houseBalance.subtract(betAmount.multiply(BigDecimal.valueOf(2))));
            }
            case "lose_double" -> {
                redisService.setBalance(playerId, playerBalance.subtract(betAmount));
                redisService.setBalance(state.getHousePlayerId(), houseBalance.add(betAmount.multiply(BigDecimal.valueOf(2))));
            }
            default -> {
                redisService.setBalance(state.getHousePlayerId(), houseBalance.add(betAmount));
            }
        }

        state.getRolledPlayers().add(playerId);
        state.getPlayerRolls().add(new RollInfo(playerId, playerModel.getDiceList(), playerModel.getComboName(), outcome));
        redisService.setGameState(gameId, state);

        return outcome;
    }

    private String resolveOutcome(ThrowModel house, ThrowModel player) {
        return PachucoRules.resolveOutcome(house, player);
    }
}
