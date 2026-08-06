package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.EventRepository;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.TransactionRepository;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameServiceImpl implements GameService {

    private static final Logger log = LoggerFactory.getLogger(GameServiceImpl.class);

    private final GameRepository gameRepository;
    private final UserRepository userRepository;
    private final EventService eventService;
    private final RedisService redisService;
    private final EventRepository eventRepository;
    private final TransactionRepository transactionRepository;
    private final int minPlayers;
    private final int maxPlayers;

    public GameServiceImpl(GameRepository gameRepository,
                           UserRepository userRepository,
                           EventService eventService,
                           RedisService redisService,
                           EventRepository eventRepository,
                           TransactionRepository transactionRepository,
                           @Value("${game.min.players:2}") int minPlayers,
                           @Value("${game.max.players:6}") int maxPlayers) {
        this.gameRepository = gameRepository;
        this.userRepository = userRepository;
        this.eventService = eventService;
        this.redisService = redisService;
        this.eventRepository = eventRepository;
        this.transactionRepository = transactionRepository;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    @Override
    @Transactional
    public GameEntity createGame(UUID creatorId) {
        UserEntity creator = userRepository.findById(creatorId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + creatorId));

        GameEntity game = new GameEntity();
        game.setCreator(creator);
        game.setStatus(GameStatus.PENDING);
        game = gameRepository.save(game);

        Map<String, Object> data = new HashMap<>();
        data.put("creatorId", creatorId.toString());
        eventService.writeEvent(game.getId(), EventType.GAME_CREATED, creatorId, data);

        GameState state = new GameState();
        state.setGameId(game.getId());
        state.setStatus("PENDING");
        state.setRoundStatus("INIT");
        state.setCurrentRound(0);
        state.setTurnOrder(new ArrayList<>());
        state.setMaxPlayers(maxPlayers);
        state.getWaitingPlayers().add(creatorId);
        state.setStatusSetTime(Instant.now());
        redisService.setGameState(game.getId(), state);

        return game;
    }

    @Override
    @Transactional
    public void joinGame(UUID gameId, UUID userId) {
        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        if (game.getStatus() != GameStatus.PENDING) {
            throw new IllegalStateException("Game is not joinable");
        }

        userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        GameState state = redisService.getGameState(gameId)
            .orElseGet(() -> {
                eventService.rebuildGameStateInRedis(gameId);
                return redisService.getGameState(gameId).orElseThrow();
            });

        if (state.getWaitingPlayers().size() + state.getReadyPlayers().size() >= maxPlayers) {
            throw new IllegalStateException("Game is full (max " + maxPlayers + " players)");
        }

        if (state.getWaitingPlayers().contains(userId) || state.getReadyPlayers().contains(userId)) {
            throw new IllegalStateException("User already in this game");
        }

        Map<String, Object> data = new HashMap<>();
        eventService.writeEvent(gameId, EventType.PLAYER_JOINED, userId, data);

        state.getWaitingPlayers().add(userId);
        redisService.setGameState(gameId, state);
    }

    @Override
    @Transactional
    public void startRound(UUID gameId, UUID housePlayerId, BigDecimal betLimit) {
        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        GameState state = redisService.getGameState(gameId)
            .orElseGet(() -> {
                eventService.rebuildGameStateInRedis(gameId);
                return redisService.getGameState(gameId).orElseThrow();
            });

        if (!"GAME_START".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not in GAME_START stage");
        }

        if (!housePlayerId.equals(state.getHousePlayerId())) {
            throw new IllegalStateException("Only the house player can start the round");
        }

        if (betLimit == null || betLimit.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Bet limit must be at least 1");
        }

        int numPlayers = state.getTurnOrder().size();
        if (numPlayers < minPlayers) {
            throw new IllegalStateException("Need at least " + minPlayers + " players to start a round");
        }

        BigDecimal houseBalance = redisService.getBalance(housePlayerId)
            .orElseThrow(() -> new IllegalStateException("House balance not available"));

        int regularPlayers = numPlayers - 1;
        BigDecimal required = betLimit.multiply(BigDecimal.valueOf(2)).multiply(BigDecimal.valueOf(regularPlayers));
        if (houseBalance.compareTo(required) < 0) {
            throw new IllegalStateException(
                "House balance %s insufficient to cover potential payouts %s"
                    .formatted(houseBalance, required));
        }

        if (game.getStatus() == GameStatus.PENDING) {
            game.setStatus(GameStatus.ACTIVE);
            game.setStartedAt(Instant.now());
            gameRepository.save(game);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("housePlayerId", housePlayerId.toString());
        data.put("betLimit", betLimit);
        eventService.writeEvent(gameId, EventType.ROUND_STARTED, housePlayerId, data);

        state.setCurrentRound(state.getCurrentRound() + 1);
        state.setRoundStatus("BET_SETTING");
        state.setHousePlayerId(housePlayerId);
        state.setBetLimit(betLimit);
        state.setHouseDice(new ArrayList<>());
        state.getBets().clear();
        state.getPlayerRolls().clear();
        state.getRolledPlayers().clear();
        state.setCurrentTurn(0);
        redisService.setGameState(gameId, state);
    }

    @Override
    public List<GameEntity> getAllGames() {
        return gameRepository.findAll();
    }

    @Override
    public Optional<GameState> getGameState(UUID gameId) {
        Optional<GameState> cached = redisService.getGameState(gameId);
        if (cached.isPresent()) return cached;

        eventService.rebuildGameStateInRedis(gameId);
        return redisService.getGameState(gameId);
    }

    @Override
    @Transactional
    public void completeGame(UUID gameId) {
        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        game.setStatus(GameStatus.COMPLETED);
        game.setCompletedAt(Instant.now());
        gameRepository.save(game);

        Map<String, Object> data = new HashMap<>();
        eventService.writeEvent(gameId, EventType.GAME_COMPLETED, null, data);

        GameState state = redisService.getGameState(gameId).orElse(new GameState());
        state.setStatus("COMPLETED");
        redisService.setGameState(gameId, state);
    }

    @Override
    @Transactional
    public void deleteGame(UUID gameId) {
        gameRepository.findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        redisService.deleteGameState(gameId);
        transactionRepository.deleteByGameId(gameId);
        eventRepository.deleteByGameId(gameId);
        gameRepository.deleteById(gameId);
    }
}
