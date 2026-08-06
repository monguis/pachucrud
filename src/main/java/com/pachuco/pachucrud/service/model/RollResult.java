package com.pachuco.pachucrud.service.model;

import com.pachuco.pachucrud.model.ThrowModel;

public class RollResult {
    private GameState state;
    private ThrowModel model;
    private String outcome;
    private boolean winner;

    public RollResult() {}

    public RollResult(GameState state, ThrowModel model, String outcome, boolean winner) {
        this.state = state;
        this.model = model;
        this.outcome = outcome;
        this.winner = winner;
    }

    public GameState getState() { return state; }
    public void setState(GameState state) { this.state = state; }
    public ThrowModel getModel() { return model; }
    public void setModel(ThrowModel model) { this.model = model; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public boolean isWinner() { return winner; }
    public void setWinner(boolean winner) { this.winner = winner; }
}
