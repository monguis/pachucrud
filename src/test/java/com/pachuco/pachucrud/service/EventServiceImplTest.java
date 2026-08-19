package com.pachuco.pachucrud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.repository.EventRepository;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.TransactionRepository;
import com.pachuco.pachucrud.repository.entity.EventEntity;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.impl.EventServiceImpl;
import com.pachuco.pachucrud.service.model.GameState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class EventServiceImplTest {

    @Mock EventRepository eventRepository;
    @Mock GameRepository gameRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock RedisService redisService;

    private EventServiceImpl eventService;
    private UUID gameId;
    private GameEntity game;
    private UUID house;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        eventService = new EventServiceImpl(eventRepository, gameRepository,
            transactionRepository, redisService, new ObjectMapper());
        gameId = UUID.randomUUID();
        game = new GameEntity();
        game.setId(gameId);
        house = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
    }

    private EventEntity event(EventType type, UUID actorId, String data) {
        EventEntity e = new EventEntity();
        e.setGame(game);
        e.setEventType(type);
        e.setActorId(actorId);
        e.setData(data);
        return e;
    }

    private String gameStartEvent() {
        return "{\"targetStage\":\"GAME_START\",\"housePlayerId\":\"" + house
            + "\",\"playerOrder\":[\"" + house + "\",\"" + alice + "\",\"" + bob + "\"]}";
    }

    @Test
    void replay_reconstructsTurnOrderAndHouseAtGameStart() {
        List<EventEntity> events = List.of(
            event(EventType.GAME_CREATED, house, "{}"),
            event(EventType.PLAYER_JOINED, alice, "{}"),
            event(EventType.PLAYER_JOINED, bob, "{}"),
            event(EventType.GAME_ADVANCED, null, gameStartEvent()));

        GameState state = eventService.replayEvents(events);

        assertEquals("GAME_START", state.getRoundStatus());
        assertEquals(house, state.getHousePlayerId());
        assertEquals(List.of(house, alice, bob), state.getTurnOrder());
    }

    @Test
    void replay_appendsMidRoundJoinerToTurnOrder() {
        List<EventEntity> events = List.of(
            event(EventType.GAME_CREATED, house, "{}"),
            event(EventType.PLAYER_JOINED, alice, "{}"),
            event(EventType.PLAYER_JOINED, bob, "{}"),
            event(EventType.GAME_ADVANCED, null, gameStartEvent()),
            event(EventType.PLAYER_JOINED, UUID.randomUUID(), "{}"));

        GameState state = eventService.replayEvents(events);

        assertEquals(4, state.getTurnOrder().size());
        assertEquals(4, state.getWaitingPlayers().size());
    }

    @Test
    void replay_reconstructsLobbyResetAfterRoundCompletion() {
        UUID dave = UUID.randomUUID();
        List<EventEntity> events = List.of(
            event(EventType.GAME_CREATED, house, "{}"),
            event(EventType.PLAYER_JOINED, alice, "{}"),
            event(EventType.PLAYER_JOINED, bob, "{}"),
            event(EventType.GAME_ADVANCED, null, gameStartEvent()),
            event(EventType.PLAYER_JOINED, dave, "{}"),
            event(EventType.ROUND_COMPLETED, null, "{\"roundNumber\":1}"),
            event(EventType.GAME_ADVANCED, null,
                "{\"targetStage\":\"ENOUGH_PLAYERS\",\"housePlayerId\":\"" + house
                    + "\",\"playerOrder\":[\"" + house + "\",\"" + alice + "\",\"" + bob + "\",\"" + dave + "\"]}"));

        GameState state = eventService.replayEvents(events);

        assertEquals("ENOUGH_PLAYERS", state.getRoundStatus());
        assertEquals(house, state.getHousePlayerId());
        assertEquals(List.of(house, alice, bob, dave), state.getTurnOrder());
        assertEquals(List.of(house, alice, bob, dave), state.getWaitingPlayers());
        assertTrue(state.getReadyPlayers().isEmpty());
    }
}