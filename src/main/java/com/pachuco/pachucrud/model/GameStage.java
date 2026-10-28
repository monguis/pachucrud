package com.pachuco.pachucrud.model;

public enum GameStage {
    INIT,
    ENOUGH_PLAYERS,
    GAME_START,
    BET_SETTING,
    PLAYERS_BET_SETTING,
    // BANK_THROW and PLAYERS_THROW are no longer entered; they stay so events from
    // games played before DICE_ROLLING still replay.
    BANK_THROW,
    PLAYERS_THROW,
    DICE_ROLLING,
    COMPLETED
}
