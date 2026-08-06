package com.pachuco.pachucrud.model;

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
}
