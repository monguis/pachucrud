package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStage;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
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
    private final RollService rollService;

    public GameStageServiceImpl(GameRepository gameRepository,
                                EventService eventService,
                                RedisService redisService,
                                RollService rollService) {
        this.gameRepository = gameRepository;
        this.eventService = eventService;
        this.redisService = redisService;
        this.rollService = rollService;
    }

    @Override
    @Transactional
    public GameState advanceToEnoughPlayers(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.INIT && state.getRoundStatus() != null) {
            throw new IllegalStateException("Game is not in " + GameStage.INIT + " stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", GameStage.ENOUGH_PLAYERS.name());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus(GameStage.ENOUGH_PLAYERS);
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToGameStart(UUID gameId, List<UUID> playerOrder) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.ENOUGH_PLAYERS) {
            throw new IllegalStateException("Game is not in " + GameStage.ENOUGH_PLAYERS + " stage");
        }

        // the first round's order is the shuffled Ready Players; after that the Rotation is rebuilt here
        List<UUID> rotation = state.getTurnOrder().isEmpty()
            ? (playerOrder == null ? List.of() : playerOrder)
            : state.rotationOfReadyPlayers();
        if (rotation.size() < 2) {
            throw new IllegalStateException("Need at least 2 players in the rotation");
        }
        state.setTurnOrder(new ArrayList<>(rotation));

        // seat 0 is the House's, so a House that isn't ready passes it to the next Ready Player
        UUID housePlayerId = rotation.get(0);
        state.setHousePlayerId(housePlayerId);

        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new NotFoundException("Game not found"));
        if (game.getStatus() == GameStatus.PENDING) {
            game.setStatus(GameStatus.ACTIVE);
            game.setStartedAt(Instant.now());
            gameRepository.save(game);
            state.setStatus("ACTIVE");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", GameStage.GAME_START.name());
        data.put("housePlayerId", housePlayerId.toString());
        data.put("playerOrder", rotation.stream().map(UUID::toString).toList());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus(GameStage.GAME_START);
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToPlayersBetSetting(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.BET_SETTING) {
            throw new IllegalStateException("Game is not in " + GameStage.BET_SETTING + " stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", GameStage.PLAYERS_BET_SETTING.name());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus(GameStage.PLAYERS_BET_SETTING);
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToDiceRolling(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.PLAYERS_BET_SETTING) {
            throw new IllegalStateException("Game is not in " + GameStage.PLAYERS_BET_SETTING + " stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", GameStage.DICE_ROLLING.name());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        GameState updatedGame = rollService.rollGamePlayers(gameId, state);
        updatedGame.setRoundStatus(GameStage.DICE_ROLLING);
        updatedGame.setStatusSetTime(Instant.now());
        // the House, at seat 0, Rolls first
        updatedGame.setCurrentTurn(0);
        redisService.setGameState(gameId, updatedGame);

        return updatedGame;
    }

    /**
     * Every hand was rolled when betting closed, so a Roll only passes the turn to the
     * next seat with a hand, which shows the roller's hand to the table.
     */
    @Override
    @Transactional
    public GameState passTurn(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.DICE_ROLLING) {
            throw new IllegalStateException("Game is not in " + GameStage.DICE_ROLLING + " stage");
        }

        int turn = state.getCurrentTurn();
        List<UUID> order = state.getTurnOrder();
        if (turn >= order.size() || !order.get(turn).equals(playerId)) {
            throw new IllegalStateException("It is not player " + playerId + "'s turn");
        }

        int next = state.nextSeatWithHand(turn + 1);
        Map<String, Object> data = new HashMap<>();
        data.put("currentTurn", next);
        eventService.writeEvent(gameId, EventType.TURN_PASSED, playerId, data);

        state.setCurrentTurn(next);
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToRoundCompleted(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.DICE_ROLLING) {
            throw new IllegalStateException("Game is not in " + GameStage.DICE_ROLLING + " stage");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", GameStage.COMPLETED.name());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.setRoundStatus(GameStage.COMPLETED);
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState advanceToNextRound(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.COMPLETED) {
            throw new IllegalStateException("Round is not completed");
        }

        List<UUID> order = new ArrayList<>(state.getTurnOrder());
        UUID currentHouse = state.getHousePlayerId();

        Map<String, Object> roundData = new HashMap<>();
        roundData.put("roundNumber", state.getCurrentRound());
        eventService.writeEvent(gameId, EventType.ROUND_COMPLETED, null, roundData, state.getCurrentRound());

        UUID nextToRoll = order.size() > 1 ? order.get(1) : null;
        UUID newHouse = currentHouse;

        if (state.getLeftPlayers().contains(currentHouse)) {
            // the House left after rolling: the next player in the Rotation takes it
            order.remove(currentHouse);
            newHouse = order.isEmpty() ? null : order.get(0);
        } else if (nextToRoll != null) {
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

        Map<String, Object> data = new HashMap<>();
        data.put("targetStage", GameStage.ENOUGH_PLAYERS.name());
        data.put("housePlayerId", newHouse != null ? newHouse.toString() : null);
        data.put("playerOrder", order.stream().map(UUID::toString).toList());
        eventService.writeEvent(gameId, EventType.GAME_ADVANCED, null, data);

        state.enterNextLobby(order, newHouse);
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(gameId, state);

        return state;
    }

    @Override
    @Transactional
    public GameState markPlayerReady(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.ENOUGH_PLAYERS) {
            throw new IllegalStateException("Game is not in " + GameStage.ENOUGH_PLAYERS + " stage");
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
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        return state;
    }

    @Override
    @Transactional
    public GameState failLobby(UUID gameId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game state not found"));

        if (state.getRoundStatus() != GameStage.INIT && state.getRoundStatus() != GameStage.ENOUGH_PLAYERS) {
            throw new IllegalStateException("Game is not in the Lobby");
        }

        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new NotFoundException("Game not found"));

        if (game.getStatus() == GameStatus.COMPLETED || game.getStatus() == GameStatus.CANCELLED) {
            throw new IllegalStateException("Game has already ended");
        }

        // a game that never played a round is cancelled; one that did simply ends
        boolean neverPlayed = game.getStatus() == GameStatus.PENDING;
        GameStatus endStatus = neverPlayed ? GameStatus.CANCELLED : GameStatus.COMPLETED;
        EventType endEvent = neverPlayed ? EventType.GAME_CANCELLED : EventType.GAME_COMPLETED;

        game.setStatus(endStatus);
        game.setCompletedAt(Instant.now());
        gameRepository.save(game);

        eventService.writeEvent(gameId, endEvent, null, new HashMap<>());

        state.setStatus(endStatus.name());
        redisService.setGameState(gameId, state);

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
