package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.impl.BetServiceImpl;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BetServiceImplTest {

    @Mock EventService eventService;
    @Mock RedisService redisService;

    private BetServiceImpl betService;
    private UUID gameId;
    private UUID house;
    private UUID alice;

    @BeforeEach
    void setUp() {
        betService = new BetServiceImpl(eventService, redisService, new BalanceCache(redisService, eventService));
        gameId = UUID.randomUUID();
        house = UUID.randomUUID();
        alice = UUID.randomUUID();
    }

    private GameState state(String roundStatus) {
        GameState state = new GameState();
        state.setGameId(gameId);
        state.setRoundStatus(roundStatus);
        state.setHousePlayerId(house);
        state.setBetLimit(BigDecimal.valueOf(100));
        state.setTurnOrder(new ArrayList<>(List.of(house, alice)));
        return state;
    }

    @Test
    void placeBet_rejectsWhenBalanceCannotCoverDoubleLoss() {
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state("PLAYERS_BET_SETTING")));
        when(redisService.getBalance(alice)).thenReturn(Optional.of(BigDecimal.valueOf(15)));

        assertThrows(IllegalStateException.class,
            () -> betService.placeBet(gameId, alice, BigDecimal.TEN));
        verify(redisService, never()).incrementBalanceIfPresent(eq(alice), any());
    }

    @Test
    void placeBet_acceptsWhenBalanceCoversDoubleLoss() {
        GameState state = state("PLAYERS_BET_SETTING");
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));
        when(redisService.getBalance(alice)).thenReturn(Optional.of(BigDecimal.valueOf(20)));

        betService.placeBet(gameId, alice, BigDecimal.TEN);

        verify(redisService).incrementBalanceIfPresent(alice, BigDecimal.valueOf(-10));
        assertEquals(1, state.getBets().size());
    }

    @Test
    void settleBet_recordsSignedNetOnRoll() {
        GameState state = state("PLAYERS_THROW");
        state.getBets().add(new GameState.BetInfo(alice, BigDecimal.TEN));
        when(redisService.getGameState(gameId)).thenReturn(Optional.of(state));

        // player pachuco vs house pair -> lose_double
        String outcome = betService.settleBet(gameId, alice,
            new ThrowModel(new Integer[] {1, 1, 2, 3, 4}),
            new ThrowModel(new Integer[] {1, 2, 3, 4, 5}));

        assertEquals("lose_double", outcome);
        GameState.RollInfo roll = state.getPlayerRolls().get(0);
        assertEquals(0, BigDecimal.valueOf(-20).compareTo(roll.getNet()));
        assertEquals("pachuco", roll.getCombo());
        verify(eventService).writeEventWithTransaction(eq(gameId), any(), eq(alice), any(),
            eq(alice), any(), any(), anyInt());
    }
}
