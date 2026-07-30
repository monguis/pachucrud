package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameServiceImpl implements GameService {

    private static final Logger log = LoggerFactory.getLogger(GameServiceImpl.class);

    private final GameRepository gameRepository;
    private final UserRepository userRepository;
    private final EventService eventService;
    private final RedisService redisService;

    public GameServiceImpl(GameRepository gameRepository,
                           UserRepository userRepository,
                           EventService eventService,
                           RedisService redisService) {
        this.gameRepository = gameRepository;
        this.userRepository = userRepository;
        this.eventService = eventService;
        this.redisService = redisService;
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
        state.setCurrentRound(0);
        state.setTurnOrder(Collections.singletonList(creatorId));
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

        Map<String, Object> data = new HashMap<>();
        eventService.writeEvent(gameId, EventType.PLAYER_JOINED, userId, data);

        GameState state = redisService.getGameState(gameId)
            .orElseGet(() -> { eventService.rebuildGameStateInRedis(gameId); return redisService.getGameState(gameId).orElseThrow(); });
        state.getTurnOrder().add(userId);
        redisService.setGameState(gameId, state);
    }

    @Override
    @Transactional
    public void startRound(UUID gameId, UUID housePlayerId, BigDecimal betLimit) {
        GameEntity game = gameRepository.findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        if (game.getStatus() == GameStatus.PENDING) {
            game.setStatus(GameStatus.ACTIVE);
            game.setStartedAt(java.time.Instant.now());
            gameRepository.save(game);
        }

        GameState state = redisService.getGameState(gameId)
            .orElseGet(() -> { eventService.rebuildGameStateInRedis(gameId); return redisService.getGameState(gameId).orElseThrow(); });

        int numPlayers = state.getTurnOrder().size();
        if (numPlayers < 2) {
            throw new IllegalStateException("Need at least 2 players to start a round");
        }

        BigDecimal houseBalance = redisService.getBalance(housePlayerId)
            .orElseThrow(() -> new IllegalStateException("House balance not available"));

        int regularPlayers = numPlayers - 1;
        BigDecimal required = betLimit.multiply(BigDecimal.valueOf(regularPlayers));
        if (houseBalance.compareTo(required) < 0) {
            throw new IllegalStateException(
                "House balance %s insufficient to cover potential payouts %s"
                    .formatted(houseBalance, required));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("housePlayerId", housePlayerId.toString());
        data.put("betLimit", betLimit);
        eventService.writeEvent(gameId, EventType.ROUND_STARTED, housePlayerId, data);

        state.setCurrentRound(state.getCurrentRound() + 1);
        state.setRoundStatus("BETTING");
        state.setHousePlayerId(housePlayerId);
        state.setBetLimit(betLimit);
        state.setHouseRoll(null);
        state.getBets().clear();
        state.getPlayerRolls().clear();
        state.getRolledPlayers().clear();
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
        game.setCompletedAt(java.time.Instant.now());
        gameRepository.save(game);

        Map<String, Object> data = new HashMap<>();
        eventService.writeEvent(gameId, EventType.GAME_COMPLETED, null, data);

        GameState state = redisService.getGameState(gameId).orElse(new GameState());
        state.setStatus("COMPLETED");
        redisService.setGameState(gameId, state);
    }
}
