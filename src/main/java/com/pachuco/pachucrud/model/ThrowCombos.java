package com.pachuco.pachucrud.model;

public enum ThrowCombos {
    PACHUCO("pachuco", 0),
    PAIR("pair", 1),
    TWOPAIRS("two_pairs", 2),
    THREEOFAKIND("three_of_a_kind", 3),
    FULL("full", 4),
    FOUROFAKIND("four_of_a_kind", 5),
    FIVEOFAKIND("five_of_a_kind", 6);

    private final String value;
    private final int rank;

    ThrowCombos(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    public String getValue() {
        return value;
    }

    public int getRank() {
        return rank;
    }
}
