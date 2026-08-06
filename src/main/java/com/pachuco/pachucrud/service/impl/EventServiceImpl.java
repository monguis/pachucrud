package com.pachuco.pachucrud.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.TransactionType;
import com.pachuco.pachucrud.repository.EventRepository;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.TransactionRepository;
import com.pachuco.pachucrud.repository.entity.EventEntity;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.repository.entity.TransactionEntity;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.GameState.BetInfo;
import com.pachuco.pachucrud.service.model.GameState.RollInfo;
import java.math.BigDecimal;
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
public class EventServiceImpl implements EventService {

    private static final Logger log = LoggerFactory.getLogger(EventServiceImpl.class);

    private final EventRepository eventRepository;
    private final GameRepository gameRepository;
    private final TransactionRepository transactionRepository;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    public EventServiceImpl(EventRepository eventRepository,
                            GameRepository gameRepository,
                            TransactionRepository transactionRepository,
                            RedisService redisService,
                            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.gameRepository = gameRepository;
        this.transactionRepository = transactionRepository;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public EventEntity writeEvent(UUID gameId, EventType eventType, UUID actorId, Map<String, Object> data) {
        return writeEvent(gameId, eventType, actorId, data, null);
    }

    @Override
    @Transactional
    public EventEntity writeEvent(UUID gameId, EventType eventType, UUID actorId, Map<String, Object> data,
                                  Integer roundNumber) {
        Integer nextSeq = null;
        GameEntity game = null;
        if (gameId != null) {
            game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
            nextSeq = eventRepository.getMaxSequenceNumber(gameId) + 1;
        }

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(data != null ? data : new HashMap<>());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }

        EventEntity event = new EventEntity();
        event.setGame(game);
        event.setSequenceNumber(nextSeq);
        event.setEventType(eventType);
        event.setActorId(actorId);
        event.setRoundNumber(roundNumber);
        event.setData(dataJson);
        event = eventRepository.save(event);

        if (gameId != null && nextSeq != null) {
            redisService.setLastSequence(gameId, nextSeq);
        }

        return event;
    }

    @Override
    @Transactional
    public EventEntity writeEventWithTransaction(UUID gameId, EventType eventType, UUID actorId,
                                                  Map<String, Object> data, UUID userId,
                                                  TransactionType txType, BigDecimal amount) {
        return writeEventWithTransaction(gameId, eventType, actorId, data, userId, txType, amount, null);
    }

    @Override
    @Transactional
    public EventEntity writeEventWithTransaction(UUID gameId, EventType eventType, UUID actorId,
                                                  Map<String, Object> data, UUID userId,
                                                  TransactionType txType, BigDecimal amount,
                                                  Integer roundNumber) {
        EventEntity event = writeEvent(gameId, eventType, actorId, data, roundNumber);

        if (userId != null && amount != null) {
            UserEntity userRef = new UserEntity();
            userRef.setId(userId);

            TransactionEntity tx = new TransactionEntity();
            tx.setUser(userRef);
            tx.setEvent(event);
            tx.setType(txType);
            tx.setAmount(amount);
            transactionRepository.save(tx);
        }

        return event;
    }

    @Override
    public void rebuildGameStateInRedis(UUID gameId) {
        List<EventEntity> events = eventRepository.findByGameIdOrderBySequenceNumberAsc(gameId);
        GameState state = replayEvents(events);
        redisService.setGameState(gameId, state);
    }

    @Override
    public BigDecimal computeUserBalance(UUID userId) {
        List<EventEntity> events = eventRepository.findByActorIdOrderByCreatedAtAsc(userId);
        BigDecimal balance = BigDecimal.ZERO;

        for (EventEntity event : events) {
            try {
                Map<String, Object> data = objectMapper.readValue(event.getData(), Map.class);

                switch (event.getEventType()) {
                    case BET_PLACED -> {
                        Object amt = data.get("amount");
                        if (amt instanceof Number n) {
                            balance = balance.subtract(BigDecimal.valueOf(n.doubleValue()));
                        }
                    }
                    case PLAYER_ROLLED -> {
                        Object outcome = data.get("outcome");
                        Object amt = data.get("amount");
                        if (!(amt instanceof Number n)) {
                            break;
                        }
                        BigDecimal bet = BigDecimal.valueOf(n.doubleValue());
                        switch (outcome != null ? outcome.toString() : "") {
                            case "win" -> balance = balance.add(bet.multiply(BigDecimal.valueOf(2)));
                            case "win_double" -> balance = balance.add(bet.multiply(BigDecimal.valueOf(3)));
                            case "lose_double" -> balance = balance.subtract(bet);
                            default -> {}
                        }
                    }
                    case DEPOSIT -> {
                        Object amt = data.get("amount");
                        if (amt instanceof Number n) {
                            balance = balance.add(BigDecimal.valueOf(n.doubleValue()));
                        }
                    }
                    case WITHDRAWAL -> {
                        Object amt = data.get("amount");
                        if (amt instanceof Number n) {
                            balance = balance.subtract(BigDecimal.valueOf(n.doubleValue()));
                        }
                    }
                    default -> {}
                }
            } catch (Exception e) {
                log.warn("Failed to parse event data for balance computation: {}", event.getId(), e);
            }
        }

        return balance;
    }

    @Override
    public void rebuildUserBalanceInRedis(UUID userId) {
        BigDecimal balance = computeUserBalance(userId);
        redisService.setBalance(userId, balance);
    }

    @Override
    public GameState replayEvents(List<EventEntity> events) {
        GameState state = new GameState();

        for (EventEntity event : events) {
            try {
                Map<String, Object> data = objectMapper.readValue(event.getData(), Map.class);

                switch (event.getEventType()) {
                    case GAME_CREATED -> {
                        state.setGameId(event.getGame().getId());
                        state.setStatus("PENDING");
                        state.setRoundStatus("INIT");
                        state.setCurrentRound(0);
                        if (event.getActorId() != null) {
                            state.getWaitingPlayers().add(event.getActorId());
                        }
                    }
                    case PLAYER_JOINED -> {
                        if (event.getActorId() != null) {
                            state.getWaitingPlayers().add(event.getActorId());
                        }
                    }
                    case ROUND_STARTED -> {
                        state.setCurrentRound(state.getCurrentRound() + 1);
                        state.setRoundStatus("PLAYERS_BET_SETTING");
                        state.setHouseDice(new ArrayList<>());
                        state.getBets().clear();
                        state.getPlayerRolls().clear();
                        state.getRolledPlayers().clear();

                        Object houseId = data.get("housePlayerId");
                        if (houseId instanceof String s) {
                            state.setHousePlayerId(UUID.fromString(s));
                        }
                        Object limit = data.get("betLimit");
                        if (limit instanceof Number n) {
                            state.setBetLimit(BigDecimal.valueOf(n.doubleValue()));
                        }
                    }
                    case BET_PLACED -> {
                        Object amt = data.get("amount");
                        if (amt instanceof Number n && event.getActorId() != null) {
                            state.getBets().add(new BetInfo(event.getActorId(), BigDecimal.valueOf(n.doubleValue())));
                        }
                    }
                    case HOUSE_ROLLED -> {
                        state.setHouseDice(parseDice(data.get("dice")));
                        state.setRoundStatus("PLAYERS_THROW");
                    }
                    case PLAYER_ROLLED -> {
                        if (event.getActorId() != null) {
                            state.getPlayerRolls().add(new RollInfo(
                                event.getActorId(),
                                parseDice(data.get("dice")),
                                data.get("combo") != null ? data.get("combo").toString() : null,
                                data.get("outcome") != null ? data.get("outcome").toString() : null));
                            state.getRolledPlayers().add(event.getActorId());
                        }
                    }
                    case ROUND_COMPLETED -> {
                        state.setRoundStatus("COMPLETED");
                    }
                    case PLAYER_READY -> {
                        if (event.getActorId() != null) {
                            state.getWaitingPlayers().remove(event.getActorId());
                            state.getReadyPlayers().add(event.getActorId());
                        }
                    }
                    case GAME_ADVANCED -> {
                        Object target = data.get("targetStage");
                        if (target instanceof String s) {
                            state.setRoundStatus(s);
                            state.setStatusSetTime(null);
                            if ("BET_SETTING".equals(s)) {
                                Object houseId = data.get("housePlayerId");
                                if (houseId instanceof String hid) {
                                    state.setHousePlayerId(UUID.fromString(hid));
                                }
                                List<UUID> order = parseUuidList(data.get("playerOrder"));
                                if (!order.isEmpty()) {
                                    state.setTurnOrder(order);
                                }
                            }
                        }
                    }
                    case BET_LIMIT_SET -> {
                        Object limit = data.get("betLimit");
                        if (limit instanceof Number n) {
                            state.setBetLimit(BigDecimal.valueOf(n.doubleValue()));
                        }
                    }
                    case GAME_COMPLETED -> {
                        state.setStatus("COMPLETED");
                    }
                    default -> {}
                }
            } catch (Exception e) {
                log.warn("Failed to replay event: {}", event.getId(), e);
            }
        }

        return state;
    }

    private List<Integer> parseDice(Object raw) {
        List<Integer> dice = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) {
                    dice.add(n.intValue());
                }
            }
        } else if (raw instanceof Number n) {
            dice.add(n.intValue());
        }
        return dice;
    }

    private List<UUID> parseUuidList(Object raw) {
        List<UUID> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    try {
                        ids.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed entries
                    }
                }
            }
        }
        return ids;
    }
}
