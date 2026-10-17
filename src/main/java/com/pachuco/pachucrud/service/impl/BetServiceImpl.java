package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.PachucoRules;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.model.TransactionType;
import com.pachuco.pachucrud.service.BalanceCache;
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
    private final BalanceCache balanceCache;

    public BetServiceImpl(EventService eventService, RedisService redisService,
                          BalanceCache balanceCache) {
        this.eventService = eventService;
        this.redisService = redisService;
        this.balanceCache = balanceCache;
    }

    @Override
    @Transactional
    public void placeBet(UUID gameId, UUID playerId, BigDecimal amount) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found in Redis"));

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

        BigDecimal playerBalance = balanceCache.get(playerId);

        // A pachuco loses 2x the bet, so the player must be able to cover that worst case.
        BigDecimal required = amount.multiply(BigDecimal.valueOf(2));
        if (playerBalance.compareTo(required) < 0) {
            throw new IllegalStateException("Insufficient balance: have " + playerBalance + ", need " + required + " (2x bet)");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("amount", amount);
        data.put("playerId", playerId.toString());

        eventService.writeEventWithTransaction(
            gameId, EventType.BET_PLACED, playerId, data,
            playerId, TransactionType.BET_HOLD, amount);

        balanceCache.addAfterCommit(playerId, amount.negate());
        state.getBets().add(new BetInfo(playerId, amount));
        redisService.setGameState(gameId, state);
    }

    @Override
    @Transactional
    public String settleBet(UUID gameId, UUID playerId, ThrowModel houseModel, ThrowModel playerModel) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found in Redis"));

        BetInfo bet = state.getBets().stream()
            .filter(b -> b.getPlayerId().equals(playerId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No bet found for player " + playerId));

        String outcome = resolveOutcome(houseModel, playerModel);

        Map<String, Object> data = new HashMap<>();
        data.put("houseDice", houseModel.getDiceList());
        data.put("playerDice", playerModel.getDiceList());
        data.put("houseCombo", houseModel.getComboName());
        data.put("playerCombo", playerModel.getComboName());

        BigDecimal playerNet = settle(gameId, state, bet, outcome, data);
        state.getPlayerRolls().add(new RollInfo(playerId, playerModel.getDiceList(), playerModel.getComboName(),
            outcome, playerNet));
        redisService.setGameState(gameId, state);

        return outcome;
    }

    @Override
    @Transactional
    public void forfeitBet(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found in Redis"));

        if (state.getRolledPlayers().contains(playerId)) {
            return;
        }
        BetInfo bet = state.getBets().stream()
            .filter(b -> b.getPlayerId().equals(playerId))
            .findFirst()
            .orElse(null);
        if (bet == null) {
            return;
        }

        // Settled as though the player threw a Pachuco, which loses double whatever
        // the House holds, so no dice are recorded for them.
        String outcome = "lose_double";
        Map<String, Object> data = new HashMap<>();
        data.put("houseDice", state.getHouseDice());
        data.put("playerDice", List.of());
        data.put("forfeit", true);

        BigDecimal playerNet = settle(gameId, state, bet, outcome, data);
        RollInfo forfeit = new RollInfo(playerId, List.of(), null, outcome, playerNet);
        forfeit.setForfeit(true);
        state.getPlayerRolls().add(forfeit);
        redisService.setGameState(gameId, state);
    }

    @Override
    @Transactional
    public void voidBet(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found in Redis"));

        if (state.getRolledPlayers().contains(playerId)) {
            return;
        }
        BetInfo bet = state.getBets().stream()
            .filter(b -> b.getPlayerId().equals(playerId))
            .findFirst()
            .orElse(null);
        if (bet == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("amount", bet.getAmount());
        data.put("playerId", playerId.toString());
        data.put("round", state.getCurrentRound());

        eventService.writeEventWithTransaction(
            gameId, EventType.BET_VOIDED, playerId, data,
            playerId, TransactionType.BET_RELEASE, bet.getAmount());

        // the hold taken when the Bet was placed comes back in full
        balanceCache.addAfterCommit(playerId, bet.getAmount());
        state.getBets().remove(bet);
        redisService.setGameState(gameId, state);
    }

    /** Records a settled Bet and moves the money; the caller records the roll it settled. */
    private BigDecimal settle(UUID gameId, GameState state, BetInfo bet, String outcome,
                              Map<String, Object> data) {
        UUID playerId = bet.getPlayerId();
        BigDecimal betAmount = bet.getAmount();

        data.put("amount", betAmount);
        data.put("playerId", playerId.toString());
        data.put("houseId", state.getHousePlayerId().toString());
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

        // The bet was already deducted when it was placed, so the Player gets it back
        // on top of their net; the House takes the opposite of the Player's net.
        BigDecimal playerNet = PachucoRules.playerNet(outcome, betAmount);
        balanceCache.addAfterCommit(playerId, playerNet.add(betAmount));
        balanceCache.addAfterCommit(state.getHousePlayerId(), playerNet.negate());

        state.getRolledPlayers().add(playerId);
        return playerNet;
    }

    private String resolveOutcome(ThrowModel house, ThrowModel player) {
        return PachucoRules.resolveOutcome(house, player);
    }
}
