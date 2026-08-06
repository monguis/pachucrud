package com.pachuco.pachucrud.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrowModelTest {

    private ThrowModel throwOf(Integer... dice) {
        return new ThrowModel(dice);
    }

    @Test
    void detectsPachucoWhenAllDiceDistinct() {
        ThrowModel model = throwOf(1, 2, 3, 4, 5);
        assertTrue(model.isPachuco());
        assertEquals("pachuco", model.getComboName());
        assertEquals(0, model.rank());
    }

    @Test
    void detectsPair() {
        ThrowModel model = throwOf(3, 3, 1, 5, 6);
        assertFalse(model.isPachuco());
        assertEquals("pair", model.getComboName());
        assertEquals(1, model.rank());
    }

    @Test
    void detectsTwoPairs() {
        ThrowModel model = throwOf(2, 2, 4, 4, 6);
        assertEquals("two_pairs", model.getComboName());
        assertEquals(2, model.rank());
    }

    @Test
    void detectsThreeOfAKind() {
        ThrowModel model = throwOf(5, 5, 5, 1, 2);
        assertEquals("three_of_a_kind", model.getComboName());
        assertEquals(3, model.rank());
    }

    @Test
    void detectsFullHouse() {
        ThrowModel model = throwOf(6, 6, 6, 2, 2);
        assertEquals("full", model.getComboName());
        assertEquals(4, model.rank());
    }

    @Test
    void detectsFourOfAKind() {
        ThrowModel model = throwOf(4, 4, 4, 4, 1);
        assertEquals("four_of_a_kind", model.getComboName());
        assertEquals(5, model.rank());
    }

    @Test
    void detectsFiveOfAKind() {
        ThrowModel model = throwOf(6, 6, 6, 6, 6);
        assertEquals("five_of_a_kind", model.getComboName());
        assertEquals(6, model.rank());
    }

    @Test
    void rejectsWrongDiceCount() {
        try {
            new ThrowModel(new Integer[] {1, 2, 3});
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("exactly 5"));
        }
    }
}
