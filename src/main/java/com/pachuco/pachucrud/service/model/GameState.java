package com.pachuco.pachucrud.service.model;

import com.pachuco.pachucrud.model.GameStage;
import com.pachuco.pachucrud.model.ThrowModel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private GameStage roundStatus;
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
     * The first seat from {@code fromIndex} on that still has a hand to Roll, or the size
     * of the Rotation when none is left. Seat 0 is the House's. Seats with no dice (no Bet,
     * a Forfeit) and players who have left are skipped.
     */
    public int nextSeatWithHand(int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < turnOrder.size(); i++) {
            UUID player = turnOrder.get(i);
            if (leftPlayers.contains(player)) {
                continue;
            }
            boolean hasHand = player.equals(housePlayerId)
                ? !houseDice.isEmpty()
                : playerRolls.stream().anyMatch(r -> r.getPlayerId().equals(player) && !r.getDice().isEmpty());
            if (hasHand) {
                return i;
            }
        }
        return turnOrder.size();
    }

    /**
     * Moves the table into the next round's Lobby with the given Rotation and House: the
     * round's Bets and hands are cleared and every seated player starts it as a Joined Player,
     * including those who joined during the round.
     */
    public void enterNextLobby(List<UUID> rotation, UUID house) {
        List<UUID> joined = new ArrayList<>(rotation);
        appendMissing(joined, waitingPlayers);
        appendMissing(joined, readyPlayers);
        turnOrder = new ArrayList<>(rotation);
        housePlayerId = house;
        leftPlayers.clear();
        bets.clear();
        playerRolls.clear();
        rolledPlayers.clear();
        houseDice = new ArrayList<>();
        betLimit = null;
        currentTurn = 0;
        waitingPlayers = joined;
        readyPlayers.clear();
        roundStatus = GameStage.ENOUGH_PLAYERS;
    }

    /**
     * The coming round's Rotation: the Ready Players who held a place in the last one keep
     * their order, and every other Ready Player goes in at the end in the order they readied.
     */
    public List<UUID> rotationOfReadyPlayers() {
        List<UUID> rotation = new ArrayList<>(turnOrder);
        rotation.retainAll(readyPlayers);
        appendMissing(rotation, readyPlayers);
        return rotation;
    }

    /** Adds each of {@code players} not already in {@code order} to its end, keeping their order. */
    private static void appendMissing(List<UUID> order, List<UUID> players) {
        for (UUID player : players) {
            if (!order.contains(player)) {
                order.add(player);
            }
        }
    }

    /**
     * Holds a seat, Joined or in the Rotation. A player who has left loses their place in the
     * Rotation, but one who comes back holds a seat again as a Joined Player.
     */
    public boolean isSeated(UUID player) {
        return seatedPlayers().contains(player);
    }

    /** Everyone holding a seat, in the Rotation or Joined, counted once. */
    public int seatedPlayerCount() {
        return seatedPlayers().size();
    }

    private Set<UUID> seatedPlayers() {
        Set<UUID> seated = new HashSet<>(turnOrder);
        seated.removeAll(leftPlayers);
        seated.addAll(waitingPlayers);
        seated.addAll(readyPlayers);
        return seated;
    }

    /** Every player who bet has rolled or forfeited. */
    public boolean allBettorsSettled() {
        long bettors = bets.stream().map(BetInfo::getPlayerId).distinct().count();
        return bettors > 0 && rolledPlayers.size() >= bettors;
    }
}
