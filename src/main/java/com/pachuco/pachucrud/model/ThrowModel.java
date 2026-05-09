package com.pachuco.pachucrud.model;

import lombok.Getter;

@Getter
public class ThrowModel {
    private Integer[] dice;
    private int mostRepeatedDice;
    private int secondMostRepeatedDice;
    private ThrowCombos combo;

    public ThrowModel(Integer[] diceThrow) {
        int[] diceOccurrences = new int[6];

        for (int die : diceThrow) {
            diceOccurrences[die] = diceOccurrences[die] + 1;
        }

        int mostRepeatedDie = 0;
        int mostOccurrences = 0;
        int secondMostRepeated = 0;
        int secondOccurrences = 0;

        for (int i = 0; i < diceOccurrences.length; i++) {
            int diceGroup = diceOccurrences[i];
            if (diceGroup == 0) {
                continue;
            }

            if (diceGroup >= mostOccurrences) {
                secondMostRepeated = mostRepeatedDie;
                secondOccurrences = mostOccurrences;
                mostRepeatedDie = i;
                mostOccurrences = diceGroup;
            } else if (mostRepeatedDie >= 3) {
                secondMostRepeated = i;
                secondOccurrences = diceGroup;
            }

        }

        ThrowCombos returnType;

        switch (mostOccurrences) {
            case 5:
                returnType = ThrowCombos.FIVEOFAKIND;
            case 4:
                returnType = ThrowCombos.FOUROFAKIND;
            case 3:
                if (secondOccurrences == 2) {
                    returnType = ThrowCombos.FULL;
                }
                returnType = ThrowCombos.THREEOFAKIND;
            case 2:
                if (secondOccurrences == 2) {
                    returnType = ThrowCombos.TWOPAIRS;
                }
                returnType = ThrowCombos.PAIR;
            default:
                returnType = ThrowCombos.PACHUCO;
        }

        this.combo = returnType;
        this.dice = diceThrow;
        this.mostRepeatedDice = mostRepeatedDie;
        this.secondMostRepeatedDice = secondMostRepeated;
    }
}
