package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.service.impl.GameStageServiceImpl;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameStageServiceImplTest {

    @Mock GameRepository gameRepository;
    @Mock EventService eventService;
    @Mock RedisService redisService;

    private GameStageServiceImpl stageService;
    private UUID gameId;
    private UUID house;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeEach
    void setUp() {
        stageService = new GameStageServiceImpl(gameRepository, eventService, redisService);
        gameId = UUID.randomUUID();
        house = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        carol = UUID.randomUUID();
    }

    private GameState completedState() {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setStatus("ACTIVE");
        state.setRoundStatus("COMPLETED");
        state.setCurrentRound(1);
        state.setHousePlayerId(house);
        state.setTurnOrder(new ArrayList<>(List.of(house, alice, bob, carol)));
        state.getPlayerRolls().add(new GameState.RollInfo(
            alice, List.of(1, 1, 1, 1, 1), "Five of a kind", "win"));
        state.getBets().add(new GameState.BetInfo(alice, BigDecimal.TEN));
        state.getRolledPlayers().add(alice);
        state.getRolledPlayers().add(bob);
        state.setHouseDice(List.of(2, 2, 2, 2, 2));
        state.setBetLimit(BigDecimal.TEN);
        state.setCurrentTurn(3);
        state.getWaitingPlayers().add(carol);
        return state;
    }

    @Test
    void advanceToNextRound_returnsToEnoughPlayersAndResetsLobby() {
        GameState state = completedState();
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.advanceToNextRound(gameId);

        assertEquals("ENOUGH_PLAYERS", result.getRoundStatus());
        assertEquals(state.getTurnOrder(), result.getWaitingPlayers());
        assertTrue(result.getReadyPlayers().isEmpty());
        assertTrue(result.getBets().isEmpty());
        assertTrue(result.getPlayerRolls().isEmpty());
        assertTrue(result.getRolledPlayers().isEmpty());
        assertTrue(result.getHouseDice().isEmpty());
        assertNull(result.getBetLimit());
        assertEquals(0, result.getCurrentTurn());
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void advanceToNextRound_rotatesHouseWhenNextToRollWon() {
        GameState state = completedState();
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.advanceToNextRound(gameId);

        assertEquals(alice, result.getHousePlayerId());
        assertEquals(List.of(alice, bob, carol, house), result.getTurnOrder());
    }

    @Test
    void advanceToNextRound_keepsHouseWhenNextToRollLost() {
        GameState state = completedState();
        state.getPlayerRolls().clear();
        state.getPlayerRolls().add(new GameState.RollInfo(
            alice, List.of(1, 1, 1, 1, 2), "Four of a kind", "loss"));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.advanceToNextRound(gameId);

        assertEquals(house, result.getHousePlayerId());
        assertEquals(List.of(house, alice, bob, carol), result.getTurnOrder());
    }

    @Test
    void advanceToNextRound_rejectsNonCompletedRound() {
        GameState state = completedState();
        state.setRoundStatus("PLAYERS_THROW");
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.advanceToNextRound(gameId));
    }
}