package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.RollResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RollServiceImpl implements RollService {

    private static final Logger log = LoggerFactory.getLogger(RollServiceImpl.class);
    private static final Random RANDOM = new Random();

    private final EventService eventService;
    private final RedisService redisService;
    private final BetService betService;
    private final GameStageService gameStageService;

    public RollServiceImpl(EventService eventService, RedisService redisService,
                           BetService betService, GameStageService gameStageService) {
        this.eventService = eventService;
        this.redisService = redisService;
        this.betService = betService;
        this.gameStageService = gameStageService;
    }

    @Override
    public ThrowModel roll5D6() {
        Integer[] dice = new Integer[] {
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
            RANDOM.nextInt(6) + 1,
        };
        return new ThrowModel(dice);
    }

    @Override
    @Transactional
    public RollResult houseRoll(UUID gameId, UUID housePlayerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"BANK_THROW".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not ready for house roll");
        }

        if (!housePlayerId.equals(state.getHousePlayerId())) {
            throw new IllegalStateException("Only the house player can roll as house");
        }

        ThrowModel model = roll5D6();

        Map<String, Object> data = new HashMap<>();
        data.put("dice", model.getDiceList());
        data.put("combo", model.getComboName());
        eventService.writeEvent(gameId, EventType.HOUSE_ROLLED, housePlayerId, data);

        state.setHouseDice(model.getDiceList());
        state.setRoundStatus("PLAYERS_THROW");
        redisService.setGameState(gameId, state);

        return new RollResult(state, model, "house_rolled", false);
    }

    @Override
    @Transactional
    public RollResult playerRoll(UUID gameId, UUID playerId) {
        GameState state = redisService.getGameState(gameId)
            .orElseThrow(() -> new IllegalArgumentException("Game state not found"));

        if (!"PLAYERS_THROW".equals(state.getRoundStatus())) {
            throw new IllegalStateException("Round is not in player rolling phase");
        }

        if (playerId.equals(state.getHousePlayerId())) {
            throw new IllegalStateException("House player cannot roll as regular player");
        }

        if (state.getRolledPlayers().contains(playerId)) {
            throw new IllegalStateException("Player has already rolled this round");
        }

        if (state.getHouseDice().isEmpty()) {
            throw new IllegalStateException("House must roll first");
        }

        boolean hasBet = state.getBets().stream()
            .anyMatch(b -> b.getPlayerId().equals(playerId));
        if (!hasBet) {
            throw new IllegalStateException("Player must place a bet before rolling");
        }

        ThrowModel playerModel = roll5D6();
        ThrowModel houseModel = new ThrowModel(toArray(state.getHouseDice()));

        String outcome = betService.settleBet(gameId, playerId, houseModel, playerModel);

        GameState freshState = redisService.getGameState(gameId).orElseThrow();

        int regularPlayers = freshState.getTurnOrder().size() - 1;
        if (regularPlayers > 0 && freshState.getRolledPlayers().size() >= regularPlayers
                && !"COMPLETED".equals(freshState.getRoundStatus())) {
            gameStageService.advanceToRoundCompleted(gameId);
            freshState = redisService.getGameState(gameId).orElseThrow();
        }

        return new RollResult(freshState, playerModel, outcome, outcome.startsWith("win"));
    }

    private Integer[] toArray(List<Integer> dice) {
        return dice.toArray(new Integer[0]);
    }
}
