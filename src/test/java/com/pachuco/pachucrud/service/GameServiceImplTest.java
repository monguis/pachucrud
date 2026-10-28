package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.GameStage;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.EventRepository;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.TransactionRepository;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.impl.GameServiceImpl;
import com.pachuco.pachucrud.service.model.GameState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceImplTest {

    @Mock GameRepository gameRepository;
    @Mock UserRepository userRepository;
    @Mock EventService eventService;
    @Mock RedisService redisService;
    @Mock EventRepository eventRepository;
    @Mock TransactionRepository transactionRepository;

    private GameServiceImpl gameService;
    private UUID gameId;
    private UUID house;
    private UUID alice;
    private UUID bob;
    private UUID dave;

    @BeforeEach
    void setUp() {
        gameService = new GameServiceImpl(gameRepository, userRepository, eventService,
            redisService, eventRepository, transactionRepository,
            new BalanceCache(redisService, eventService), 2, 6);
        gameId = UUID.randomUUID();
        house = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        dave = UUID.randomUUID();
    }

    private GameEntity gameWithStatus(GameStatus status) {
        GameEntity game = new GameEntity();
        game.setId(gameId);
        game.setStatus(status);
        return game;
    }

    private GameState activeState() {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setStatus("ACTIVE");
        state.setRoundStatus(GameStage.PLAYERS_THROW);
        state.setTurnOrder(new ArrayList<>(List.of(house, alice, bob)));
        state.setHousePlayerId(house);
        return state;
    }

    @Test
    void joinGame_rejectsCompletedGame() {
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.COMPLETED)));

        assertThrows(IllegalStateException.class, () -> gameService.joinGame(gameId, dave));
    }

    @Test
    void joinGame_rejectsCancelledGame() {
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.CANCELLED)));

        assertThrows(IllegalStateException.class, () -> gameService.joinGame(gameId, dave));
    }

    @Test
    void joinGame_allowsActiveGame() {
        GameState state = activeState();
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.ACTIVE)));
        when(userRepository.findById(dave)).thenReturn(Optional.of(new UserEntity()));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        gameService.joinGame(gameId, dave);

        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void joinGame_keepsAMidRoundJoinerOutOfTheRotation() {
        GameState state = activeState();
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.ACTIVE)));
        when(userRepository.findById(dave)).thenReturn(Optional.of(new UserEntity()));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        gameService.joinGame(gameId, dave);

        assertEquals(List.of(house, alice, bob), state.getTurnOrder());
        assertTrue(state.getWaitingPlayers().contains(dave));
    }

    @Test
    void joinGame_doesNotAppendToTurnOrderBeforeGameStarts() {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setStatus("PENDING");
        state.setRoundStatus(GameStage.INIT);
        state.setTurnOrder(new ArrayList<>());
        state.getWaitingPlayers().add(house);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.PENDING)));
        when(userRepository.findById(alice)).thenReturn(Optional.of(new UserEntity()));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        gameService.joinGame(gameId, alice);

        assertTrue(state.getTurnOrder().isEmpty());
        assertTrue(state.getWaitingPlayers().contains(alice));
    }

    @Test
    void joinGame_isANoOpForAPlayerAlreadySeated() {
        GameState state = activeState();
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.ACTIVE)));
        when(userRepository.findById(alice)).thenReturn(Optional.of(new UserEntity()));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        gameService.joinGame(gameId, alice);

        verifyNoInteractions(eventService);
        verify(redisService, never()).setGameState(any(), any());
    }

    @Test
    void joinGame_rejectsFullGame() {
        GameState state = activeState();
        state.setTurnOrder(new ArrayList<>(List.of(
            house, alice, bob, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(gameWithStatus(GameStatus.ACTIVE)));
        when(userRepository.findById(dave)).thenReturn(Optional.of(new UserEntity()));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> gameService.joinGame(gameId, dave));
    }
}
