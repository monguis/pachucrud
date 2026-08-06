package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.impl.RollServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RollServiceImplTest {

    @Mock RedisService redisService;
    @Mock EventService eventService;
    @Mock BetService betService;
    @Mock GameStageService gameStageService;

    private RollServiceImpl rollService;
    private UUID gameId;
    private UUID house;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeEach
    void setUp() {
        rollService = new RollServiceImpl(eventService, redisService, betService, gameStageService);
        gameId = UUID.randomUUID();
        house = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        carol = UUID.randomUUID();
    }

    private GameState bettableState(String roundStatus) {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setRoundStatus(roundStatus);
        state.setHousePlayerId(house);
        state.setTurnOrder(new ArrayList<>(List.of(house, alice, bob, carol)));
        state.setCurrentTurn(0);
        return state;
    }

    private void bet(GameState state, UUID playerId, double amount) {
        state.getBets().add(new GameState.BetInfo(playerId, BigDecimal.valueOf(amount)));
    }

    @Test
    void houseRoll_setsCurrentTurnToFirstBettor() {
        GameState state = bettableState("BANK_THROW");
        bet(state, bob, 10);
        bet(state, carol, 10);

        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        rollService.houseRoll(gameId, house);

        assertEquals("PLAYERS_THROW", state.getRoundStatus());
        assertEquals(2, state.getCurrentTurn());
        assertTrue(!state.getHouseDice().isEmpty());
    }

    @Test
    void playerRoll_rejectsOutOfTurnPlayer() {
        GameState state = bettableState("PLAYERS_THROW");
        bet(state, alice, 10);
        bet(state, carol, 10);
        state.setHouseDice(List.of(3, 3, 3, 3, 3));
        state.setCurrentTurn(1);

        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        assertThrows(IllegalStateException.class, () -> rollService.playerRoll(gameId, carol));
    }

    @Test
    void playerRoll_advancesTurnSkippingNonBettor() {
        GameState initial = bettableState("PLAYERS_THROW");
        bet(initial, alice, 10);
        bet(initial, carol, 10);
        initial.setHouseDice(List.of(3, 3, 3, 3, 3));
        initial.setCurrentTurn(1);

        GameState fresh = bettableState("PLAYERS_THROW");
        bet(fresh, alice, 10);
        bet(fresh, carol, 10);
        fresh.setHouseDice(List.of(3, 3, 3, 3, 3));
        fresh.setCurrentTurn(1);
        fresh.getRolledPlayers().add(alice);

        when(redisService.getGameState(gameId))
            .thenReturn(Optional.of(initial), Optional.of(fresh));
        when(betService.settleBet(eq(gameId), eq(alice), any(ThrowModel.class), any(ThrowModel.class)))
            .thenReturn("loss");

        rollService.playerRoll(gameId, alice);

        assertEquals(3, fresh.getCurrentTurn());
        verify(redisService).setGameState(gameId, fresh);
        verify(gameStageService, never()).advanceToRoundCompleted(gameId);
    }

    @Test
    void playerRoll_completesRoundWhenAllBettorsRolledIgnoringNonBettors() {
        GameState initial = bettableState("PLAYERS_THROW");
        bet(initial, alice, 10);
        bet(initial, carol, 10);
        initial.setHouseDice(List.of(3, 3, 3, 3, 3));
        initial.setCurrentTurn(3);

        GameState fresh = bettableState("PLAYERS_THROW");
        bet(fresh, alice, 10);
        bet(fresh, carol, 10);
        fresh.setHouseDice(List.of(3, 3, 3, 3, 3));
        fresh.setCurrentTurn(3);
        fresh.getRolledPlayers().add(alice);
        fresh.getRolledPlayers().add(carol);

        GameState completed = bettableState("COMPLETED");
        completed.setHouseDice(List.of(3, 3, 3, 3, 3));

        when(redisService.getGameState(gameId))
            .thenReturn(Optional.of(initial), Optional.of(fresh), Optional.of(completed));
        when(betService.settleBet(eq(gameId), eq(carol), any(ThrowModel.class), any(ThrowModel.class)))
            .thenReturn("loss");

        rollService.playerRoll(gameId, carol);

        verify(gameStageService).advanceToRoundCompleted(gameId);
    }
}
