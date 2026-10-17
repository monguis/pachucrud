package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.LeaveService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveServiceImpl implements LeaveService {

    // Round stages in which the House holds the seat it is judged from.
    private static final Set<String> ROUND_STAGES =
        Set.of("GAME_START", "BET_SETTING", "PLAYERS_BET_SETTING", "BANK_THROW", "PLAYERS_THROW", "COMPLETED");

    private final EventService eventService;
    private final RedisService redisService;
    private final BetService betService;
    private final GameStageService gameStageService;

    public LeaveServiceImpl(EventService eventService, RedisService redisService,
                            BetService betService, GameStageService gameStageService) {
        this.eventService = eventService;
        this.redisService = redisService;
        this.betService = betService;
        this.gameStageService = gameStageService;
    }

    @Override
    @Transactional
    public GameState leaveGame(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (!isSeated(state, playerId)) {
            return state;
        }

        boolean beforeHouseRolls = ROUND_STAGES.contains(state.getRoundStatus())
            && state.getHouseDice().isEmpty();
        boolean isHouse = ROUND_STAGES.contains(state.getRoundStatus())
            && playerId.equals(state.getHousePlayerId());
        if (isHouse && beforeHouseRolls) {
            // the next player in the Rotation takes the House
            recordLeave(gameId, playerId, false);
            List<UUID> order = new ArrayList<>(state.getTurnOrder());
            order.remove(playerId);
            return abandonRound(gameId, order, order.isEmpty() ? null : order.get(0));
        }
        if (isHouse) {
            return houseLeavesAfterRolling(gameId, state, playerId);
        }

        // placing a Bet commits the player to it
        betService.forfeitBet(gameId, playerId);
        state = redisService.getGameState(gameId).orElseThrow();

        recordLeave(gameId, playerId, false);
        state.getWaitingPlayers().remove(playerId);
        state.getReadyPlayers().remove(playerId);
        leaveRotation(state, playerId);

        if (beforeHouseRolls && noOneLeftToPlayTheHouse(state)) {
            return abandonRound(gameId, new ArrayList<>(state.getTurnOrder()), state.getHousePlayerId());
        }
        if ("PLAYERS_THROW".equals(state.getRoundStatus())) {
            // the turn never waits on a player who has gone
            state.setCurrentTurn(state.nextBettorIndex(state.getCurrentTurn()));
            if (state.allBettorsSettled()) {
                redisService.setGameState(gameId, state);
                return gameStageService.advanceToRoundCompleted(gameId);
            }
        }
        redisService.setGameState(gameId, state);

        return state;
    }

    /**
     * An Abandoned Round ends before the House rolls, so there is nothing to settle
     * against: every Bet not yet settled is Released, and the next round's Lobby
     * starts with the given Rotation and House.
     */
    private GameState abandonRound(UUID gameId, List<UUID> order, UUID newHouse) {
        GameState state = redisService.getGameState(gameId).orElseThrow();
        for (UUID bettor : state.getBets().stream().map(GameState.BetInfo::getPlayerId).toList()) {
            betService.releaseBet(gameId, bettor);
        }
        state = redisService.getGameState(gameId).orElseThrow();

        Map<String, Object> abandoned = new HashMap<>();
        abandoned.put("roundNumber", state.getCurrentRound());
        abandoned.put("housePlayerId", state.getHousePlayerId().toString());
        eventService.writeEvent(gameId, EventType.ROUND_ABANDONED, null, abandoned);

        Map<String, Object> advanced = new HashMap<>();
        advanced.put("targetStage", "ENOUGH_PLAYERS");
        advanced.put("housePlayerId", newHouse != null ? newHouse.toString() : null);
        advanced.put("playerOrder", order.stream().map(UUID::toString).toList());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, advanced);

        state.setTurnOrder(order);
        state.setHousePlayerId(newHouse);
        state.getLeftPlayers().clear();
        state.getBets().clear();
        state.getPlayerRolls().clear();
        state.getRolledPlayers().clear();
        state.setHouseDice(new ArrayList<>());
        state.setBetLimit(null);
        state.setCurrentTurn(0);
        state.getWaitingPlayers().clear();
        state.getWaitingPlayers().addAll(order);
        state.getReadyPlayers().clear();
        state.setRoundStatus("ENOUGH_PLAYERS");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    /**
     * The House's Throw stands, so it keeps seat 0 and the round settles against it;
     * the seat passes on when the next round starts.
     */
    private GameState houseLeavesAfterRolling(UUID gameId, GameState state, UUID house) {
        recordLeave(gameId, house, true);
        state.getLeftPlayers().add(house);
        redisService.setGameState(gameId, state);
        return state;
    }

    /** Every seat but the House's is empty, so nobody can bet against it. */
    private static boolean noOneLeftToPlayTheHouse(GameState state) {
        return state.getTurnOrder().stream().allMatch(p -> p.equals(state.getHousePlayerId()));
    }

    private void recordLeave(UUID gameId, UUID playerId, boolean keepsSeat) {
        Map<String, Object> data = new HashMap<>();
        data.put("playerId", playerId.toString());
        if (keepsSeat) {
            data.put("keepsSeat", true);
        }
        eventService.writeEvent(gameId, EventType.PLAYER_LEFT, playerId, data);
    }

    /** The current turn is an index into the Rotation, so it shifts with the seats ahead of it. */
    private static void leaveRotation(GameState state, UUID playerId) {
        int seat = state.getTurnOrder().indexOf(playerId);
        if (seat < 0) {
            return;
        }
        state.getTurnOrder().remove(seat);
        if (seat < state.getCurrentTurn()) {
            state.setCurrentTurn(state.getCurrentTurn() - 1);
        }
    }

    private static boolean isSeated(GameState state, UUID playerId) {
        if (state.getLeftPlayers().contains(playerId)) {
            return false;
        }
        return state.getWaitingPlayers().contains(playerId)
            || state.getReadyPlayers().contains(playerId)
            || state.getTurnOrder().contains(playerId);
    }
}
