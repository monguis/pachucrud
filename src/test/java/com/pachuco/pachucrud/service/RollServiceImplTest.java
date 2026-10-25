package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.impl.RollServiceImpl;
import com.pachuco.pachucrud.service.model.GameState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RollServiceImplTest {

    @Mock RedisService redisService;
    @Mock EventService eventService;
    @Mock BetService betService;

    private RollServiceImpl rollService;
    private UUID gameId;
    private UUID house;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        rollService = new RollServiceImpl(eventService, redisService, betService);
        gameId = UUID.randomUUID();
        house = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
    }

    private GameState bettingState() {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setRoundStatus("PLAYERS_BET_SETTING");
        state.setHousePlayerId(house);
        state.setTurnOrder(new ArrayList<>(List.of(house, alice, bob)));
        state.getReadyPlayers().addAll(List.of(house, alice, bob));
        return state;
    }

    private void bet(GameState state, UUID playerId, double amount) {
        state.getBets().add(new GameState.BetInfo(playerId, BigDecimal.valueOf(amount)));
    }

    /** Settles like the real BetService does: the player counts as rolled. */
    private void settleEveryoneAsLoss(GameState state) {
        when(betService.settleBet_New(eq(state), any(UUID.class), any(ThrowModel.class), any(ThrowModel.class)))
            .thenAnswer(invocation -> {
                state.getRolledPlayers().add(invocation.getArgument(1));
                return "loss";
            });
    }

    @Test
    void roll5D6_throwsFiveDiceBetweenOneAndSix() {
        ThrowModel model = rollService.roll5D6();

        assertEquals(5, model.getDiceList().size());
        model.getDiceList().forEach(d -> assertTrue(d >= 1 && d <= 6, "die out of range: " + d));
    }

    @Test
    void rollGamePlayers_rejectsRoundOutsideBetting() {
        GameState state = bettingState();
        state.setRoundStatus("BET_SETTING");
        bet(state, alice, 10);
        bet(state, bob, 10);

        assertThrows(IllegalStateException.class, () -> rollService.rollGamePlayers(gameId, state));

        verifyNoInteractions(eventService, betService, redisService);
    }

    @Test
    void rollGamePlayers_rejectsBeforeRollingWhenAReadyPlayerHasNoBet() {
        GameState state = bettingState();
        bet(state, alice, 10);

        assertThrows(IllegalStateException.class, () -> rollService.rollGamePlayers(gameId, state));

        verifyNoInteractions(eventService, betService, redisService);
        assertEquals(List.of(), state.getHouseDice());
    }

    @Test
    void rollGamePlayers_settlesEveryReadyPlayerButTheHouseAndSavesState() {
        GameState state = bettingState();
        bet(state, alice, 10);
        bet(state, bob, 10);
        settleEveryoneAsLoss(state);

        GameState result = rollService.rollGamePlayers(gameId, state);

        assertSame(state, result);
        assertEquals(5, state.getHouseDice().size());
        verify(eventService).writeEvent(eq(gameId), eq(EventType.HOUSE_ROLLED), eq(house), anyMap());
        verify(betService).settleBet_New(eq(state), eq(alice), any(ThrowModel.class), any(ThrowModel.class));
        verify(betService).settleBet_New(eq(state), eq(bob), any(ThrowModel.class), any(ThrowModel.class));
        verify(betService, never()).settleBet_New(eq(state), eq(house), any(), any());
        verify(redisService).setGameState(gameId, state);
    }

    @Test
    void rollGamePlayers_settlesEveryPlayerAgainstTheSameHouseThrow() {
        GameState state = bettingState();
        bet(state, alice, 10);
        bet(state, bob, 10);
        List<ThrowModel> houseThrows = new ArrayList<>();
        when(betService.settleBet_New(eq(state), any(UUID.class), any(ThrowModel.class), any(ThrowModel.class)))
            .thenAnswer(invocation -> {
                houseThrows.add(invocation.getArgument(2));
                state.getRolledPlayers().add(invocation.getArgument(1));
                return "loss";
            });

        rollService.rollGamePlayers(gameId, state);

        assertEquals(2, houseThrows.size());
        assertSame(houseThrows.get(0), houseThrows.get(1));
        assertEquals(state.getHouseDice(), houseThrows.get(0).getDiceList());
    }

    @Test
    void rollGamePlayers_doesNotSaveStateWhenASettlementFails() {
        GameState state = bettingState();
        bet(state, alice, 10);
        bet(state, bob, 10);
        when(betService.settleBet_New(eq(state), eq(alice), any(ThrowModel.class), any(ThrowModel.class)))
            .thenReturn("loss");
        when(betService.settleBet_New(eq(state), eq(bob), any(ThrowModel.class), any(ThrowModel.class)))
            .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> rollService.rollGamePlayers(gameId, state));

        verify(redisService, never()).setGameState(any(), any());
    }

    @Test
    void rollGamePlayers_doesNotSaveStateWhenABettorIsLeftUnsettled() {
        GameState state = bettingState();
        bet(state, alice, 10);
        bet(state, bob, 10);
        // settles without marking anyone as rolled
        when(betService.settleBet_New(eq(state), any(UUID.class), any(ThrowModel.class), any(ThrowModel.class)))
            .thenReturn("loss");

        assertThrows(IllegalStateException.class, () -> rollService.rollGamePlayers(gameId, state));

        verify(redisService, never()).setGameState(any(), any());
    }
}
