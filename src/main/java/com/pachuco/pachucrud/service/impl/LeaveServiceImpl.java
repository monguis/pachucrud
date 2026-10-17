package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.LeaveService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveServiceImpl implements LeaveService {

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

        // placing a Bet commits the player to it
        betService.forfeitBet(gameId, playerId);
        state = redisService.getGameState(gameId).orElseThrow();

        Map<String, Object> data = new HashMap<>();
        data.put("playerId", playerId.toString());
        eventService.writeEvent(gameId, EventType.PLAYER_LEFT, playerId, data);

        state.getWaitingPlayers().remove(playerId);
        state.getReadyPlayers().remove(playerId);
        leaveRotation(state, playerId);

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
        return state.getWaitingPlayers().contains(playerId)
            || state.getReadyPlayers().contains(playerId)
            || state.getTurnOrder().contains(playerId);
    }
}
