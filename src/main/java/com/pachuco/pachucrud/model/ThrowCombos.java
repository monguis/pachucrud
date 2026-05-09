package com.pachuco.pachucrud.model;

public enum ThrowCombos {
    FIVEOFAKIND("five_of_a_kind"),
    FOUROFAKIND("four_of_a_kind"),
    FULL("full"),
    PACHUCO("pachuco"),
    PAIR("pair"),
    THREEOFAKIND("three_of_a_kind"),
    TWOPAIRS("two_pairs");

    private final String value;

    // Constructor (must be private or package-private)
    ThrowCombos(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
