package com.pachuco.pachucrud.service.model;

import com.pachuco.pachucrud.model.ThrowModel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GameState {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class BetInfo {
        private UUID playerId;
        private BigDecimal amount;

        public BetInfo(UUID playerId, BigDecimal amount) {
            this.playerId = playerId;
            this.amount = amount;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RollInfo {
        private UUID playerId;
        private List<Integer> dice = new ArrayList<>();
        private String combo;
        private String outcome;
        private BigDecimal net;
        /** The player left before rolling, so their Bet was settled without dice. */
        private boolean forfeit;

        public RollInfo(UUID playerId, List<Integer> dice, String combo, String outcome) {
            this(playerId, dice, combo, outcome, null);
        }
        public RollInfo(UUID playerId, List<Integer> dice, String combo, String outcome, BigDecimal net) {
            this.playerId = playerId;
            this.dice = dice != null ? dice : new ArrayList<>();
            this.combo = combo;
            this.outcome = outcome;
            this.net = net;
        }

        public void setDice(List<Integer> dice) { this.dice = dice != null ? dice : new ArrayList<>(); }
    }

    private UUID gameId;
    private String status;
    private int currentRound;
    private String roundStatus;
    private UUID housePlayerId;
    private BigDecimal betLimit;
    private List<UUID> turnOrder = new ArrayList<>();
    private List<BetInfo> bets = new ArrayList<>();
    private List<Integer> houseDice = new ArrayList<>();
    private List<RollInfo> playerRolls = new ArrayList<>();
    private List<UUID> rolledPlayers = new ArrayList<>();
    private List<UUID> waitingPlayers = new ArrayList<>();
    private List<UUID> readyPlayers = new ArrayList<>();
    // Players who left during this round but still hold a seat: a House that left
    // after rolling keeps seat 0 so the round can settle against its Throw.
    private List<UUID> leftPlayers = new ArrayList<>();
    private int currentTurn;
    private boolean needsShuffling;
    private Instant statusSetTime;
    private List<UUID> playersMarkedForDeletion = new ArrayList<>();
    private int maxPlayers;
    private Instant lastActivity;

    public void setHouseDice(List<Integer> houseDice) { this.houseDice = houseDice != null ? houseDice : new ArrayList<>(); }
    public void setLeftPlayers(List<UUID> leftPlayers) { this.leftPlayers = leftPlayers != null ? leftPlayers : new ArrayList<>(); }

    /** Derived from houseDice so it can never drift; serialized for clients, ignored on read. */
    public String getHouseCombo() {
        if (houseDice == null || houseDice.size() != 5) {
            return null;
        }
        return new ThrowModel(houseDice.toArray(new Integer[0])).getComboName();
    }
    /**
     * The seat of the first player from {@code fromIndex} on who bet and has not rolled,
     * or the size of the Rotation when none is left. Seat 0 is the House's.
     */
    public int nextBettorIndex(int fromIndex) {
        for (int i = Math.max(1, fromIndex); i < turnOrder.size(); i++) {
            UUID player = turnOrder.get(i);
            if (player.equals(housePlayerId)) {
                continue;
            }
            boolean hasBet = bets.stream().anyMatch(b -> b.getPlayerId().equals(player));
            if (hasBet && !rolledPlayers.contains(player)) {
                return i;
            }
        }
        return turnOrder.size();
    }

    /** Every player who bet has rolled or forfeited. */
    public boolean allBettorsSettled() {
        long bettors = bets.stream().map(BetInfo::getPlayerId).distinct().count();
        return bettors > 0 && rolledPlayers.size() >= bettors;
    }
}
