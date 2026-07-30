package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameStageServiceImpl implements GameStageService {

    private static final Logger log = LoggerFactory.getLogger(GameStageServiceImpl.class);
    private static final Random RANDOM = new Random();
    private static final int MIN_PLAYERS = 2;
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

        if (state.getWaitingPlayers().size() < MIN_PLAYERS) {
            throw new IllegalStateException(
                "Need at least " + MIN_PLAYERS + " players, have " + state.getWaitingPlayers().size());
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
    public GameState advanceToGameStart(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"ENOUGH_PLAYERS".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in ENOUGH_PLAYERS stage");
        }

        List<UUID> readyPlayers = state.getReadyPlayers();
        if (readyPlayers.size() < MIN_PLAYERS) {
            throw new IllegalStateException(
                "Need at least " + MIN_PLAYERS + " ready players, have " + readyPlayers.size());
        }

        List<UUID> playerOrder = state.getTurnOrder();

        if (state.getHousePlayerId() == null && playerOrder.isEmpty()) {
            playerOrder = new ArrayList<>(readyPlayers);
            Collections.shuffle(playerOrder, RANDOM);
            state.setTurnOrder(playerOrder);
        }

        if (state.isNeedsShuffling()) {
            UUID removed = playerOrder.remove(0);
            playerOrder.add(removed);
            state.setNeedsShuffling(false);
        }

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
        data.put("targetStage", "BET_SETTING");
        data.put("housePlayerId", housePlayerId.toString());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus("BET_SETTING");
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToNextRound(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        Set<UUID> markedForDeletion = new HashSet<>(state.getPlayersMarkedForDeletion());

        List<UUID> newWaitingPlayers = new ArrayList<>();
        for (UUID playerId : state.getReadyPlayers()) {
            if (!markedForDeletion.contains(playerId)) {
                newWaitingPlayers.add(playerId);
            }
        }

        List<UUID> newPlayerOrder = new ArrayList<>();
        for (UUID playerId : state.getTurnOrder()) {
            if (!markedForDeletion.contains(playerId)) {
                newPlayerOrder.add(playerId);
            }
        }

        state.setWaitingPlayers(newWaitingPlayers);
        state.setTurnOrder(newPlayerOrder);
        state.setReadyPlayers(new ArrayList<>());
        state.getBets().clear();
        state.getPlayerRolls().clear();
        state.getRolledPlayers().clear();
        state.setHouseRoll(null);
        state.setBetLimit(null);
        state.setHousePlayerId(null);
        state.setPlayersMarkedForDeletion(new ArrayList<>());

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
    public GameState markPlayerReady(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"ENOUGH_PLAYERS".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Game is not in ENOUGH_PLAYERS stage");
        }

        if (state.getReadyPlayers().contains(playerId)) {
            throw new IllegalStateException("Player is already ready");
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
}
