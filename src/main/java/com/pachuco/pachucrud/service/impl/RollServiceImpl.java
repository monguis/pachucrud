package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
import com.pachuco.pachucrud.service.model.GameState;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RollServiceImpl implements RollService {

    private static final Logger log = LoggerFactory.getLogger(RollServiceImpl.class);
    private static final Random RANDOM = new Random();

    private final EventService eventService;
    private final RedisService redisService;
    private final BetService betService;

    public RollServiceImpl(EventService eventService, RedisService redisService,
                           BetService betService) {
        this.eventService = eventService;
        this.redisService = redisService;
        this.betService = betService;
    }

    @Override
    public int rollD6() {
        return RANDOM.nextInt(6) + 1;
    }

    @Override
    @Transactional
    public GameState houseRoll(UUID gameId, UUID housePlayerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"BANK_THROW".equals(state.getRoundStatus()) && !"PLAYERS_BET_SETTING".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not ready for house roll");
        }

        if (!housePlayerId.equals(state.getHousePlayerId())) {
            throw new IllegalStateException("Only the house player can roll as house");
        }

        int diceValue = rollD6();

        Map<String, Object> data = new HashMap<>();
        data.put("diceValue", diceValue);
        eventService.writeEvent(gameId, EventType.HOUSE_ROLLED, housePlayerId, data);

        state.setHouseRoll(diceValue);
        state.setRoundStatus("PLAYERS_THROW");
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState playerRoll(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"PLAYERS_THROW".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not in player rolling phase");
        }

        if (playerId.equals(state.getHousePlayerId())) {
            throw new IllegalStateException("House player cannot roll as regular player");
        }

        if (state.getRolledPlayers().contains(playerId)) {
            throw new IllegalStateException("Player has already rolled this round");
        }

        if (state.getHouseRoll() == null) {
            throw new IllegalStateException("House must roll first");
        }

        boolean hasBet = state.getBets().stream()
            .anyMatch(b -> b.getPlayerId().equals(playerId));
        if (!hasBet) {
            throw new IllegalStateException("Player must place a bet before rolling");
        }

        int diceValue = rollD6();

        betService.settleBet(gameId, playerId, state.getHouseRoll(), diceValue);

        GameState freshState = redisService.getGameState(gameId).orElseThrow();

        boolean allBettingPlayersRolled = freshState.getBets().stream()
            .allMatch(b -> freshState.getRolledPlayers().contains(b.getPlayerId()));
        if (allBettingPlayersRolled) {
            Map<String, Object> roundData = new HashMap<>();
            roundData.put("roundNumber", freshState.getCurrentRound());

            UUID currentHouse = freshState.getHousePlayerId();
            UUID nextToRoll = findNextToRoll(freshState.getTurnOrder(), currentHouse);
            boolean houseChanges = nextToRoll != null
                && freshState.getPlayerRolls().stream()
                    .anyMatch(r -> r.getPlayerId().equals(nextToRoll)
                                && r.getDiceValue() > freshState.getHouseRoll());

            if (houseChanges) {
                shiftTurnOrder(freshState, nextToRoll);
            }

            eventService.writeEvent(gameId, EventType.ROUND_COMPLETED, null, roundData);

            freshState.setRoundStatus("COMPLETED");
            redisService.setGameState(gameId, freshState);
        }

        return freshState;
    }

    @Override
    public ThrowModel roll5D6() {
        Integer[] diceThrow = new Integer[] {
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
        };
        return new ThrowModel(diceThrow);
    }

    private UUID findNextToRoll(java.util.List<UUID> turnOrder, UUID houseId) {
        int houseIdx = turnOrder.indexOf(houseId);
        if (houseIdx < 0 || houseIdx >= turnOrder.size() - 1) return null;
        return turnOrder.get(houseIdx + 1);
    }

    private void shiftTurnOrder(GameState state, UUID newHouseId) {
        java.util.List<UUID> order = state.getTurnOrder();
        UUID oldHouse = state.getHousePlayerId();
        state.setHousePlayerId(newHouseId);

        java.util.List<UUID> rotated = new java.util.ArrayList<>();
        int newHouseIdx = order.indexOf(newHouseId);
        for (int i = newHouseIdx; i < order.size(); i++) {
            rotated.add(order.get(i));
        }
        rotated.add(oldHouse);
        for (int i = 0; i < newHouseIdx; i++) {
            UUID p = order.get(i);
            if (!p.equals(oldHouse)) {
                rotated.add(p);
            }
        }
        state.setTurnOrder(rotated);
    }
}
