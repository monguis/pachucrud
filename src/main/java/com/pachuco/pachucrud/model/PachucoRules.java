package com.pachuco.pachucrud.model;

import java.math.BigDecimal;

public final class PachucoRules {

    private PachucoRules() {}

    public static String resolveOutcome(ThrowModel house, ThrowModel player) {
        boolean housePachuco = house.isPachuco();
        boolean playerPachuco = player.isPachuco();

        if (playerPachuco) {
            return "lose_double";
        }
        if (housePachuco) {
            return "win_double";
        }

        return player.rank() > house.rank() ? "win" : "loss";
    }

    /** Player's signed net result for a settled bet, per the payout matrix. */
    public static BigDecimal playerNet(String outcome, BigDecimal bet) {
        return switch (outcome) {
            case "win" -> bet;
            case "win_double" -> bet.multiply(BigDecimal.valueOf(2));
            case "lose_double" -> bet.multiply(BigDecimal.valueOf(2)).negate();
            default -> bet.negate();
        };
    }
}
