package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
import com.pachuco.pachucrud.service.model.GameState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public RollServiceImpl(EventService eventService, RedisService redisService,
                           BetService betService) {
        this.eventService = eventService;
        this.redisService = redisService;
        this.betService = betService;
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

    // One transaction for the whole round: if any settlement fails, the House roll and
    // every earlier settlement roll back with it, so a retry can't pay a player twice.
    @Override
    @Transactional
    public GameState rollGamePlayers(UUID gameId, GameState initialState) {
        if (!"PLAYERS_BET_SETTING".equals(initialState.getRoundStatus())) {
            throw new IllegalStateException("Round is not ready for player rolls");
        }

        List<UUID> withoutBet = initialState.getReadyPlayers().stream()
            .filter(playerId -> initialState.getBets().stream().noneMatch(b -> b.getPlayerId().equals(playerId)))
            .toList();
        
        // Bank does not bet
        if (withoutBet.size() != 1) {
            throw new IllegalStateException("Players must place a bet before rolling: " + withoutBet);
        }

        // Creates the House player roll
        UUID housePlayerId = initialState.getHousePlayerId();
        ThrowModel houseRoll = roll5D6();

        Map<String, Object> data = new HashMap<>();
        data.put("dice", houseRoll.getDiceList());
        data.put("combo", houseRoll.getComboName());
        eventService.writeEvent(gameId, EventType.HOUSE_ROLLED, housePlayerId, data);

        initialState.setHouseDice(houseRoll.getDiceList());

        for (UUID playerId : initialState.getReadyPlayers()) {
            if(playerId.equals(housePlayerId)){
                continue;
            }
            betService.settleBet_New(initialState, playerId, houseRoll, roll5D6());
        }

        if (!initialState.allBettorsSettled()) {
            throw new IllegalStateException("Not every bettor was settled");
        }

        // Last, so nothing after it can fail and roll back settlements Redis already shows.
        redisService.setGameState(gameId, initialState);
        return initialState;
    }

    private Integer[] toArray(List<Integer> dice) {
        return dice.toArray(new Integer[0]);
    }
}
