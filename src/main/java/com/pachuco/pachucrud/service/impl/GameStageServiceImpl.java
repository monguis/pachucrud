package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.GameState.RollInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameStageServiceImpl implements GameStageService {

    private static final Logger log = LoggerFactory.getLogger(GameStageServiceImpl.class);
    private static final long INITIAL_WAIT_MINUTES = 5;
    private static final long ENOUGH_PLAYERS_WAIT_MINUTES = 5;

    private final GameRepository gameRepository;
    private final EventService eventService;
    private final RedisService redisService;

    public GameStageServiceImpl(GameRepository gameRepository,
                                EventService eventService,
                                RedisService redisService) {
        this.gameRepository = gameRepository;
        this.eventService = eventService;
        this.redisService = redisService;
    }

    @Override
    @Transactional
    public GameState advanceToEnoughPlayers(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"INIT".equals(state.getRoundStatus()) && state.getRoundStatus() != null) {
            throw new IllegalStateException("Game is not in INIT stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", "ENOUGH_PLAYERS");
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus("ENOUGH_PLAYERS");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToGameStart(UUID gameId, List<UUID> playerOrder) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"ENOUGH_PLAYERS".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in ENOUGH_PLAYERS stage");
        }

        if (playerOrder == null || playerOrder.size() < 2) {
            throw new IllegalStateException("Need at least 2 players in the rotation");
        }

        List<UUID> mergedOrder = new ArrayList<>(playerOrder);
        for (UUID playerId : state.getTurnOrder()) {
            if (!mergedOrder.contains(playerId)) {
                mergedOrder.add(playerId);
            }
        }
        state.setTurnOrder(mergedOrder);

        UUID housePlayerId = playerOrder.get(0);
        state.setHousePlayerId(housePlayerId);

        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game not found"));
        if (game.getStatus() == GameStatus.PENDING) {
            game.setStatus(GameStatus.ACTIVE);
            game.setStartedAt(Instant.now());
            gameRepository.save(game);
            state.setStatus("ACTIVE");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", "GAME_START");
        data.put("housePlayerId", housePlayerId.toString());
        data.put("playerOrder", mergedOrder.stream().map(UUID::toString).toList());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus("GAME_START");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToPlayersBetSetting(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"BET_SETTING".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in BET_SETTING stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", "PLAYERS_BET_SETTING");
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus("PLAYERS_BET_SETTING");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToBankThrow(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"PLAYERS_BET_SETTING".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in PLAYERS_BET_SETTING stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", "BANK_THROW");
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus("BANK_THROW");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToRoundCompleted(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"PLAYERS_THROW".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in PLAYERS_THROW stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", "COMPLETED");
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus("COMPLETED");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToNextRound(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"COMPLETED".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not completed");
        }

        List<UUID> order = new ArrayList<>(state.getTurnOrder());
        UUID currentHouse = state.getHousePlayerId();

        Map<String, Object> roundData = new HashMap<>();
        roundData.put("roundNumber", state.getCurrentRound());
        eventService.writeEvent(gameId, EventType.ROUND_COMPLETED, null, roundData, state.getCurrentRound());

        UUID nextToRoll = order.size() > 1 ? order.get(1) : null;
        UUID newHouse = currentHouse;

        if (nextToRoll != null) {
            String outcome = state.getPlayerRolls().stream()
                .filter(r -> r.getPlayerId().equals(nextToRoll))
                .map(RollInfo::getOutcome)
                .findFirst()
                .orElse(null);

            if (outcome != null && outcome.startsWith("win")) {
                newHouse = nextToRoll;
                order = rotateToNewHouse(order, currentHouse, nextToRoll);
            }
        }

        state.setHousePlayerId(newHouse);
        state.setTurnOrder(order);

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", "GAME_START");
        data.put("housePlayerId", newHouse.toString());
        data.put("playerOrder", order.stream().map(UUID::toString).toList());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.getBets().clear();
        state.getPlayerRolls().clear();
        state.getRolledPlayers().clear();
        state.setHouseDice(new ArrayList<>());
        state.setBetLimit(null);
        state.setCurrentTurn(0);
        state.setRoundStatus("GAME_START");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState markPlayerReady(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"ENOUGH_PLAYERS".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in ENOUGH_PLAYERS stage");
        }

        if (state.getReadyPlayers().contains(playerId)) {
            return state;
        }

        if (!state.getWaitingPlayers().contains(playerId)) {
            throw new IllegalStateException("Player is not waiting in this game");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("playerId", playerId.toString());
        eventService.writeEvent(gameId, EventType.PLAYER_READY, playerId, data);

        state.getWaitingPlayers().remove(playerId);
        state.getReadyPlayers().add(playerId);
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    public GameState getCurrentTurn(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        return state;
    }

    private List<UUID> rotateToNewHouse(List<UUID> order, UUID oldHouse, UUID newHouse) {
        List<UUID> rotated = new ArrayList<>();
        int newHouseIdx = order.indexOf(newHouse);
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
        return rotated;
    }
}
