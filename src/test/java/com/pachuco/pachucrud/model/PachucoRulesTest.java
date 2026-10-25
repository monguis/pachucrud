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
    void sameComboHigherFaceWins() {
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(2, 2, 2, 2, 2),
            throwOf(5, 5, 5, 5, 5)));
    }

    @Test
    void sameComboLowerFaceLoses() {
        assertEquals("loss", PachucoRules.resolveOutcome(
            throwOf(4, 4, 1, 2, 3),
            throwOf(3, 3, 4, 5, 6)));
    }

    @Test
    void fullIsDecidedByTripleBeforePair() {
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(3, 3, 3, 6, 6),
            throwOf(4, 4, 4, 1, 1)));
    }

    @Test
    void twoPairsIsDecidedByHighPairThenLowPair() {
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(5, 5, 4, 4, 6),
            throwOf(6, 6, 1, 1, 2)));
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(6, 6, 3, 3, 5),
            throwOf(6, 6, 4, 4, 1)));
    }

    @Test
    void diceOutsideTheComboAreIgnoredSoTheHouseWinsTheTie() {
        assertEquals("loss", PachucoRules.resolveOutcome(
            throwOf(3, 3, 3, 3, 1),
            throwOf(3, 3, 3, 3, 6)));
        assertEquals("loss", PachucoRules.resolveOutcome(
            throwOf(4, 4, 1, 2, 3),
            throwOf(4, 4, 6, 5, 3)));
        assertEquals("loss", PachucoRules.resolveOutcome(
            throwOf(6, 6, 4, 4, 1),
            throwOf(6, 6, 4, 4, 2)));
    }

    @Test
    void fullBeatsThreeOfAKind() {
        assertEquals("win", PachucoRules.resolveOutcome(
            throwOf(1, 1, 1, 2, 3),
            throwOf(2, 2, 2, 3, 3)));
    }
}
