package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.TransactionType;
import com.pachuco.pachucrud.repository.entity.EventEntity;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventService {
    EventEntity writeEvent(UUID gameId, EventType eventType, UUID actorId, Map<String, Object> data);
    EventEntity writeEvent(UUID gameId, EventType eventType, UUID actorId, Map<String, Object> data,
                           Integer roundNumber);
    EventEntity writeEventWithTransaction(UUID gameId, EventType eventType, UUID actorId,
                                          Map<String, Object> data, UUID userId,
                                          TransactionType txType, BigDecimal amount);
    EventEntity writeEventWithTransaction(UUID gameId, EventType eventType, UUID actorId,
                                          Map<String, Object> data, UUID userId,
                                          TransactionType txType, BigDecimal amount,
                                          Integer roundNumber);
    void rebuildGameStateInRedis(UUID gameId);
    BigDecimal computeUserBalance(UUID userId);
    void rebuildUserBalanceInRedis(UUID userId);
    GameState replayEvents(List<EventEntity> events);
}
