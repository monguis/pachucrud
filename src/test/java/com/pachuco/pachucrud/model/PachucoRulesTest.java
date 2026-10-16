package com.pachuco.pachucrud.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PachucoRulesTest {

    private ThrowModel throwOf(Integer... dice) {
        return new ThrowModel(dice);
    }

    @Test
    void bothPachucoHouseWinsDouble() {
        assertEquals("lose_double", PachucoRules.resolveOutcome(
            throwOf(1, 2, 3, 4, 6),
            throwOf(1, 2, 3, 4, 5)));
    }

    @Test
    void playerPachucoLosesDouble() {
        assertEquals("lose_double", PachucoRules.resolveOutcome(
            throwOf(1, 1, 1, 1, 2),
            throwOf(1, 2, 3, 4, 5)));
    }

    @Test
    void housePachucoPlayerWinsDouble() {
        assertEquals("win_double", PachucoRules.resolveOutcome(
            throwOf(1, 2, 3, 4, 5),
            throwOf(1, 1, 1, 1, 2)));
    }

    @Test
    void higherRankWins() {
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(1, 1, 2, 3, 4),
            throwOf(2, 2, 2, 2, 2)));
    }

    @Test
    void lowerRankLoses() {
        assertEquals("loss", PachucoRules.resolveOutcome(
            throwOf(6, 6, 6, 6, 6),
            throwOf(1, 1, 2, 3, 4)));
    }

    @Test
    void tieGoesToHouse() {
        assertEquals("loss", PachucoRules.resolveOutcome(
            throwOf(3, 3, 3, 3, 3),
            throwOf(3, 3, 3, 3, 3)));
    }

    @Test
    void playerNetFollowsPayoutMatrix() {
        BigDecimal bet = BigDecimal.TEN;
        assertEquals(0, BigDecimal.valueOf(10).compareTo(PachucoRules.playerNet("win", bet)));
        assertEquals(0, BigDecimal.valueOf(20).compareTo(PachucoRules.playerNet("win_double", bet)));
        assertEquals(0, BigDecimal.valueOf(-10).compareTo(PachucoRules.playerNet("loss", bet)));
        assertEquals(0, BigDecimal.valueOf(-20).compareTo(PachucoRules.playerNet("lose_double", bet)));
    }

    @Test
    void fullBeatsThreeOfAKind() {
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(1, 1, 1, 2, 3),
            throwOf(2, 2, 2, 3, 3)));
    }
}
