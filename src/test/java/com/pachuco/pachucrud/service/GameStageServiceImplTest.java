package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.GameStage;
import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.impl.GameStageServiceImpl;
import com.pachuco.pachucrud.service.model.GameState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameStageServiceImplTest {

    @Mock GameRepository gameRepository;
    @Mock EventService eventService;
    @Mock RedisService redisService;
    @Mock RollService rollService;

    private GameStageServiceImpl stageService;
    private UUID gameId;
    private UUID house;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeEach
    void setUp() {
        stageService = new GameStageServiceImpl(gameRepository, eventService, redisService, rollService);
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
        state.setRoundStatus(GameStage.COMPLETED);
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

        assertEquals(GameStage.ENOUGH_PLAYERS, result.getRoundStatus());
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

    private GameState lobbyState(GameStage roundStatus, String status) {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setStatus(status);
        state.setRoundStatus(roundStatus);
        state.getWaitingPlayers().add(house);
        return state;
    }

    private GameEntity gameEntity(GameStatus status) {
        GameEntity game = new GameEntity();
        game.setId(gameId);
        game.setStatus(status);
        return game;
    }

    @Test
    void failLobby_cancelsGameThatNeverPlayedARound() {
        GameState state = lobbyState(GameStage.INIT, "PENDING");
        GameEntity game = gameEntity(GameStatus.PENDING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        GameState result = stageService.failLobby(gameId);

        assertEquals("CANCELLED", result.getStatus());
        assertEquals(GameStatus.CANCELLED, game.getStatus());
        assertNotNull(game.getCompletedAt());
        verify(gameRepository).save(game);
        verify(eventService).writeEvent(eq(gameId), eq(EventType.GAME_CANCELLED), isNull(), anyMap());
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void failLobby_completesGameThatAlreadyPlayedRounds() {
        GameState state = lobbyState(GameStage.ENOUGH_PLAYERS, "ACTIVE");
        GameEntity game = gameEntity(GameStatus.ACTIVE);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        GameState result = stageService.failLobby(gameId);

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(GameStatus.COMPLETED, game.getStatus());
        assertNotNull(game.getCompletedAt());
        verify(eventService).writeEvent(eq(gameId), eq(EventType.GAME_COMPLETED), isNull(), anyMap());
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void failLobby_rejectsGameOutsideTheLobby() {
        GameState state = lobbyState(GameStage.PLAYERS_THROW, "ACTIVE");
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.failLobby(gameId));
        verify(gameRepository, never()).save(any());
    }

    @Test
    void failLobby_rejectsGameThatAlreadyEnded() {
        GameState state = lobbyState(GameStage.INIT, "CANCELLED");
        GameEntity game = gameEntity(GameStatus.CANCELLED);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        assertThrows(IllegalStateException.class, () -> stageService.failLobby(gameId));
        assertEquals(GameStatus.CANCELLED, game.getStatus());
        verify(gameRepository, never()).save(any());
    }

    @Test
    void advanceToNextRound_rejectsNonCompletedRound() {
        GameState state = completedState();
        state.setRoundStatus(GameStage.DICE_ROLLING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.advanceToNextRound(gameId));
    }

    private GameState roundState(GameStage roundStatus) {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setStatus("ACTIVE");
        state.setRoundStatus(roundStatus);
        state.setHousePlayerId(house);
        state.setTurnOrder(new ArrayList<>(List.of(house, alice)));
        return state;
    }

    @Test
    void advanceToDiceRolling_rollsTheRoundAndMovesToDiceRolling() {
        GameState state = roundState(GameStage.PLAYERS_BET_SETTING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(rollService.rollGamePlayers(gameId, state)).thenReturn(state);

        GameState result = stageService.advanceToDiceRolling(gameId);

        assertEquals(GameStage.DICE_ROLLING, result.getRoundStatus());
        assertNotNull(result.getStatusSetTime());
        verify(rollService).rollGamePlayers(gameId, state);
        verify(eventService).writeEvent(eq(gameId), eq(EventType.GAME_ADVANCED), isNull(),
            eq(Map.of("targetStage", "DICE_ROLLING")));
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void advanceToDiceRolling_rejectsRoundOutsideBetting() {
        GameState state = roundState(GameStage.BET_SETTING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.advanceToDiceRolling(gameId));

        verifyNoInteractions(rollService, eventService);
        verify(redisService, never()).setGameState(any(), any());
    }

    @Test
    void advanceToDiceRolling_savesNothingWhenTheRollFails() {
        GameState state = roundState(GameStage.PLAYERS_BET_SETTING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(rollService.rollGamePlayers(gameId, state)).thenThrow(new IllegalStateException("no bet"));

        assertThrows(IllegalStateException.class, () -> stageService.advanceToDiceRolling(gameId));

        assertEquals(GameStage.PLAYERS_BET_SETTING, state.getRoundStatus());
        verify(redisService, never()).setGameState(any(), any());
    }

    @Test
    void advanceToRoundCompleted_completesARolledRound() {
        GameState state = roundState(GameStage.DICE_ROLLING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.advanceToRoundCompleted(gameId);

        assertEquals(GameStage.COMPLETED, result.getRoundStatus());
        verify(eventService).writeEvent(eq(gameId), eq(EventType.GAME_ADVANCED), isNull(),
            eq(Map.of("targetStage", "COMPLETED")));
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void advanceToRoundCompleted_rejectsRoundThatHasNotRolled() {
        GameState state = roundState(GameStage.PLAYERS_BET_SETTING);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.advanceToRoundCompleted(gameId));
        verify(redisService, never()).setGameState(any(), any());
    }

    @Test
    void advanceToDiceRolling_givesTheFirstTurnToTheHouse() {
        GameState state = roundState(GameStage.PLAYERS_BET_SETTING);
        state.setCurrentTurn(2);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(rollService.rollGamePlayers(gameId, state)).thenReturn(state);

        GameState result = stageService.advanceToDiceRolling(gameId);

        assertEquals(0, result.getCurrentTurn());
    }

    /** House at seat 0, then alice, bob (no Bet, so no hand) and carol, every hand rolled. */
    private GameState rollingState() {
        GameState state = roundState(GameStage.DICE_ROLLING);
        state.setTurnOrder(new ArrayList<>(List.of(house, alice, bob, carol)));
        state.setHouseDice(List.of(2, 2, 2, 2, 2));
        state.getPlayerRolls().add(new GameState.RollInfo(alice, List.of(1, 1, 1, 1, 1), "Five of a kind", "win"));
        state.getPlayerRolls().add(new GameState.RollInfo(carol, List.of(3, 3, 4, 4, 6), "Two pair", "lose"));
        return state;
    }

    @Test
    void passTurn_movesTheTurnToTheNextSeatWithAHand() {
        GameState state = rollingState();
        state.setCurrentTurn(1);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.passTurn(gameId, alice);

        assertEquals(3, result.getCurrentTurn());
        verify(eventService).writeEvent(eq(gameId), eq(EventType.TURN_PASSED), eq(alice),
            eq(Map.of("currentTurn", 3)));
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void passTurn_byTheLastHandRunsTheTurnOut() {
        GameState state = rollingState();
        state.setCurrentTurn(3);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.passTurn(gameId, carol);

        assertEquals(4, result.getCurrentTurn());
    }

    @Test
    void passTurn_skipsAPlayerWhoHasLeft() {
        GameState state = rollingState();
        state.getLeftPlayers().add(alice);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        GameState result = stageService.passTurn(gameId, house);

        assertEquals(3, result.getCurrentTurn());
    }

    @Test
    void passTurn_rejectsAPlayerWhoseTurnItIsNot() {
        GameState state = rollingState();
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.passTurn(gameId, alice));

        verifyNoInteractions(eventService);
        verify(redisService, never()).setGameState(any(), any());
    }

    @Test
    void passTurn_rejectsRoundOutsideDiceRolling() {
        GameState state = rollingState();
        state.setRoundStatus(GameStage.COMPLETED);
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> stageService.passTurn(gameId, house));
        verify(redisService, never()).setGameState(any(), any());
    }
}