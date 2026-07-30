package com.pachuco.pachucrud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisServiceImpl implements RedisService {

    private static final Logger log = LoggerFactory.getLogger(RedisServiceImpl.class);
    private static final String KEY_BALANCE = "user:%s:balance";
    private static final String KEY_GAME_STATE = "game:%s:state";
    private static final String KEY_LAST_SEQ = "game:%s:events:last_seq";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisServiceImpl(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isReachable() {
        try {
            return "PONG".equals(redis.getConnectionFactory().getConnection().ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setBalance(UUID userId, BigDecimal balance) {
        redis.opsForValue().set(KEY_BALANCE.formatted(userId.toString()), balance.toPlainString());
    }

    @Override
    public Optional<BigDecimal> getBalance(UUID userId) {
        String val = redis.opsForValue().get(KEY_BALANCE.formatted(userId.toString()));
        return val != null ? Optional.of(new BigDecimal(val)) : Optional.empty();
    }

    @Override
    public void setGameState(UUID gameId, GameState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(KEY_GAME_STATE.formatted(gameId.toString()), json);
        } catch (Exception e) {
            log.error("Failed to serialize game state for {}", gameId, e);
        }
    }

    @Override
    public Optional<GameState> getGameState(UUID gameId) {
        String json = redis.opsForValue().get(KEY_GAME_STATE.formatted(gameId.toString()));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, GameState.class));
        } catch (Exception e) {
            log.error("Failed to deserialize game state for {}", gameId, e);
            return Optional.empty();
        }
    }

    @Override
    public void deleteGameState(UUID gameId) {
        redis.delete(KEY_GAME_STATE.formatted(gameId.toString()));
        redis.delete(KEY_LAST_SEQ.formatted(gameId.toString()));
    }

    @Override
    public void setLastSequence(UUID gameId, int seq) {
        redis.opsForValue().set(KEY_LAST_SEQ.formatted(gameId.toString()), String.valueOf(seq));
    }

    @Override
    public Optional<Integer> getLastSequence(UUID gameId) {
        String val = redis.opsForValue().get(KEY_LAST_SEQ.formatted(gameId.toString()));
        return val != null ? Optional.of(Integer.parseInt(val)) : Optional.empty();
    }
}
