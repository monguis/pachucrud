package com.pachuco.pachucrud.model;

import java.util.List;
import lombok.Getter;

@Getter
public class ThrowModel {
    private Integer[] dice;
    private int mostRepeatedDice;
    private int secondMostRepeatedDice;
    private ThrowCombos combo;

    public ThrowModel(Integer[] diceThrow) {
        if (diceThrow == null || diceThrow.length != 5) {
            throw new IllegalArgumentException("A Pachuco throw requires exactly 5 dice");
        }

        this.dice = diceThrow.clone();

        int[] diceOccurrences = new int[7];

        for (int die : diceThrow) {
            if (die < 1 || die > 6) {
                throw new IllegalArgumentException("Dice value out of range: " + die);
            }
            diceOccurrences[die] = diceOccurrences[die] + 1;
        }

        int mostRepeatedDie = 0;
        int mostOccurrences = 0;
        int secondMostRepeated = 0;
        int secondOccurrences = 0;
        int distinctValues = 0;

        for (int i = 1; i < diceOccurrences.length; i++) {
            int diceGroup = diceOccurrences[i];
            if (diceGroup == 0) {
                continue;
            }
            distinctValues++;

            if (diceGroup >= mostOccurrences) {
                secondMostRepeated = mostRepeatedDie;
                secondOccurrences = mostOccurrences;
                mostRepeatedDie = i;
                mostOccurrences = diceGroup;
            } else if (diceGroup >= secondOccurrences) {
                secondMostRepeated = i;
                secondOccurrences = diceGroup;
            }
        }

        this.mostRepeatedDice = mostRepeatedDie;
        this.secondMostRepeatedDice = secondMostRepeated;

        if (distinctValues == 5) {
            this.combo = ThrowCombos.PACHUCO;
        } else {
            switch (mostOccurrences) {
                case 5:
                    this.combo = ThrowCombos.FIVEOFAKIND;
                    break;
                case 4:
                    this.combo = ThrowCombos.FOUROFAKIND;
                    break;
                case 3:
                    this.combo = secondOccurrences == 2
                        ? ThrowCombos.FULL
                        : ThrowCombos.THREEOFAKIND;
                    break;
                case 2:
                    this.combo = secondOccurrences == 2
                        ? ThrowCombos.TWOPAIRS
                        : ThrowCombos.PAIR;
                    break;
                default:
                    this.combo = ThrowCombos.PACHUCO;
                    break;
            }
        }
    }

    public boolean isPachuco() {
        return combo == ThrowCombos.PACHUCO;
    }

    public int rank() {
        return combo.getRank();
    }

    public List<Integer> getDiceList() {
        return List.of(dice);
    }

    public String getComboName() {
        return combo.getValue();
    }
}
