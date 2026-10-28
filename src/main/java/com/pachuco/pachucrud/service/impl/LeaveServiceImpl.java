package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStage;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.LeaveService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.util.ArrayList;
import java.util.EnumSet;
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
    private static final Set<GameStage> ROUND_STAGES = EnumSet.of(
        GameStage.GAME_START, GameStage.BET_SETTING, GameStage.PLAYERS_BET_SETTING,
        GameStage.DICE_ROLLING, GameStage.COMPLETED);

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

        if (!state.isSeated(playerId)) {
            return state;
        }

        boolean beforeHouseRolls = ROUND_STAGES.contains(state.getRoundStatus())
            && state.getHouseDice().isEmpty();
        boolean isHouse = ROUND_STAGES.contains(state.getRoundStatus())
            && playerId.equals(state.getHousePlayerId());
        if (isHouse && beforeHouseRolls) {
            // the next player in the Rotation takes the House
            recordLeave(gameId, state, playerId, false);
            redisService.setGameState(gameId, state);
            List<UUID> rotation = new ArrayList<>(state.getTurnOrder());
            rotation.remove(playerId);
            return abandonRound(gameId, rotation, rotation.isEmpty() ? null : rotation.get(0));
        }
        if (isHouse) {
            return houseLeavesAfterRolling(gameId, state, playerId);
        }

        // placing a Bet commits the player to it; once betting has closed the hand
        // is already settled and stands
        betService.forfeitBet(gameId, playerId);
        state = redisService.getGameState(gameId).orElseThrow();

        recordLeave(gameId, state, playerId, false);
        int turnBefore = state.getCurrentTurn();
        leaveRotation(state, playerId);

        if (beforeHouseRolls && noOneLeftToPlayTheHouse(state)) {
            return abandonRound(gameId, new ArrayList<>(state.getTurnOrder()), state.getHousePlayerId());
        }
        skipTurnOfDeparted(gameId, state, turnBefore);
        redisService.setGameState(gameId, state);

        return state;
    }

    /**
     * The turn never waits on a player who has gone: during DICE_ROLLING it moves on to
     * the next seat with a hand. The pass is recorded so a replay lands on the same seat.
     */
    private void skipTurnOfDeparted(UUID gameId, GameState state, int turnBefore) {
        if (state.getRoundStatus() != GameStage.DICE_ROLLING) {
            return;
        }
        int turn = state.nextSeatWithHand(state.getCurrentTurn());
        state.setCurrentTurn(turn);
        if (turn != turnBefore) {
            Map<String, Object> data = new HashMap<>();
            data.put("currentTurn", turn);
            eventService.writeEvent(gameId, EventType.TURN_PASSED, null, data);
        }
    }

    /**
     * An Abandoned Round ends before the House rolls, so there is nothing to settle
     * against: every Bet not yet settled is Released, and the next round's Lobby
     * starts with the given Rotation and House.
     */
    private GameState abandonRound(UUID gameId, List<UUID> rotation, UUID newHouse) {
        GameState state = redisService.getGameState(gameId).orElseThrow();
        for (UUID bettor : state.getBets().stream().map(GameState.BetInfo::getPlayerId).toList()) {
            betService.releaseBet(gameId, bettor);
        }
        state = redisService.getGameState(gameId).orElseThrow();

        Map<String, Object> abandoned = new HashMap<>();
        abandoned.put("roundNumber", state.getCurrentRound());
        abandoned.put("housePlayerId", state.getHousePlayerId().toString());
        eventService.writeEvent(gameId, EventType.ROUND_ABANDONED, null, abandoned);

        return gameStageService.openNextLobby(gameId, state, rotation, newHouse);
    }

    /**
     * The House's Throw stands, so it keeps seat 0 and the round settles against it;
     * the seat passes on when the next round starts.
     */
    private GameState houseLeavesAfterRolling(UUID gameId, GameState state, UUID house) {
        recordLeave(gameId, state, house, true);
        state.getLeftPlayers().add(house);
        skipTurnOfDeparted(gameId, state, state.getCurrentTurn());
        redisService.setGameState(gameId, state);
        return state;
    }

    /** Every seat but the House's is empty, so nobody can bet against it. */
    private static boolean noOneLeftToPlayTheHouse(GameState state) {
        return state.getTurnOrder().stream().allMatch(p -> p.equals(state.getHousePlayerId()));
    }

    /**
     * Records the Leave and, as its replay does, drops the player from the Joined and Ready
     * Players, so the next Lobby doesn't seat them again.
     */
    private void recordLeave(UUID gameId, GameState state, UUID playerId, boolean keepsSeat) {
        Map<String, Object> data = new HashMap<>();
        data.put("playerId", playerId.toString());
        if (keepsSeat) {
            data.put("keepsSeat", true);
        }
        eventService.writeEvent(gameId, EventType.PLAYER_LEFT, playerId, data);
        state.getWaitingPlayers().remove(playerId);
        state.getReadyPlayers().remove(playerId);
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
}
