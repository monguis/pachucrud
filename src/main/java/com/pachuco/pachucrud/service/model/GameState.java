package com.pachuco.pachucrud.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GameState {

    public static class BetInfo {
        private UUID playerId;
        private BigDecimal amount;

        public BetInfo() {}
        public BetInfo(UUID playerId, BigDecimal amount) {
            this.playerId = playerId;
            this.amount = amount;
        }

        public UUID getPlayerId() { return playerId; }
        public void setPlayerId(UUID playerId) { this.playerId = playerId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }

    public static class RollInfo {
        private UUID playerId;
        private List<Integer> dice = new ArrayList<>();
        private String combo;
        private String outcome;

        public RollInfo() {}
        public RollInfo(UUID playerId, List<Integer> dice, String combo, String outcome) {
            this.playerId = playerId;
            this.dice = dice != null ? dice : new ArrayList<>();
            this.combo = combo;
            this.outcome = outcome;
        }

        public UUID getPlayerId() { return playerId; }
        public void setPlayerId(UUID playerId) { this.playerId = playerId; }
        public List<Integer> getDice() { return dice; }
        public void setDice(List<Integer> dice) { this.dice = dice != null ? dice : new ArrayList<>(); }
        public String getCombo() { return combo; }
        public void setCombo(String combo) { this.combo = combo; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
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
    private int currentTurn;
    private boolean needsShuffling;
    private Instant statusSetTime;
    private List<UUID> playersMarkedForDeletion = new ArrayList<>();
    private int maxPlayers;
    private Instant lastActivity;

    public GameState() {}

    public UUID getGameId() { return gameId; }
    public void setGameId(UUID gameId) { this.gameId = gameId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public String getRoundStatus() { return roundStatus; }
    public void setRoundStatus(String roundStatus) { this.roundStatus = roundStatus; }
    public UUID getHousePlayerId() { return housePlayerId; }
    public void setHousePlayerId(UUID housePlayerId) { this.housePlayerId = housePlayerId; }
    public BigDecimal getBetLimit() { return betLimit; }
    public void setBetLimit(BigDecimal betLimit) { this.betLimit = betLimit; }
    public List<UUID> getTurnOrder() { return turnOrder; }
    public void setTurnOrder(List<UUID> turnOrder) { this.turnOrder = turnOrder; }
    public List<BetInfo> getBets() { return bets; }
    public void setBets(List<BetInfo> bets) { this.bets = bets; }
    public List<Integer> getHouseDice() { return houseDice; }
    public void setHouseDice(List<Integer> houseDice) { this.houseDice = houseDice != null ? houseDice : new ArrayList<>(); }
    public List<RollInfo> getPlayerRolls() { return playerRolls; }
    public void setPlayerRolls(List<RollInfo> playerRolls) { this.playerRolls = playerRolls; }
    public List<UUID> getRolledPlayers() { return rolledPlayers; }
    public void setRolledPlayers(List<UUID> rolledPlayers) { this.rolledPlayers = rolledPlayers; }
    public List<UUID> getWaitingPlayers() { return waitingPlayers; }
    public void setWaitingPlayers(List<UUID> waitingPlayers) { this.waitingPlayers = waitingPlayers; }
    public List<UUID> getReadyPlayers() { return readyPlayers; }
    public void setReadyPlayers(List<UUID> readyPlayers) { this.readyPlayers = readyPlayers; }
    public int getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(int currentTurn) { this.currentTurn = currentTurn; }
    public boolean isNeedsShuffling() { return needsShuffling; }
    public void setNeedsShuffling(boolean needsShuffling) { this.needsShuffling = needsShuffling; }
    public Instant getStatusSetTime() { return statusSetTime; }
    public void setStatusSetTime(Instant statusSetTime) { this.statusSetTime = statusSetTime; }
    public List<UUID> getPlayersMarkedForDeletion() { return playersMarkedForDeletion; }
    public void setPlayersMarkedForDeletion(List<UUID> playersMarkedForDeletion) { this.playersMarkedForDeletion = playersMarkedForDeletion; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public Instant getLastActivity() { return lastActivity; }
    public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }
}
