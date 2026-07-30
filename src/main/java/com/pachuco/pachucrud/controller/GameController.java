package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.model.GameStatus;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.GameService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
import com.pachuco.pachucrud.service.model.GameState;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;
import pachuco_proto.GameServiceGrpc;
import pachuco_proto.Games;

@GrpcService
public class GameController extends GameServiceGrpc.GameServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GameController.class);

    private final GameService gameService;
    private final BetService betService;
    private final RollService rollService;
    private final RedisService redisService;
    private final GameRepository gameRepository;

    public GameController(GameService gameService, BetService betService,
                          RollService rollService, RedisService redisService,
                          GameRepository gameRepository) {
        this.gameService = gameService;
        this.betService = betService;
        this.rollService = rollService;
        this.redisService = redisService;
        this.gameRepository = gameRepository;
    }

    @Override
    public void getAllGames(Games.EmptyRequest request,
                            StreamObserver<Games.GetAllGamesResponse> responseObserver) {
        var games = gameRepository.findAll();
        var builder = Games.GetAllGamesResponse.newBuilder();

        for (GameEntity g : games) {
            var state = redisService.getGameState(g.getId());
            int totalPlayers = state.map(s -> s.getTurnOrder().size()).orElse(0);
            BigDecimal betLimit = state.map(GameState::getBetLimit).orElse(BigDecimal.ZERO);

            builder.addGames(Games.GameMessage.newBuilder()
                .setId(g.getId().toString())
                .setStatus(g.getStatus().name())
                .setBetLimit(betLimit.intValue())
                .setTotalPlayers(totalPlayers)
                .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void createGame(Games.CreateGameRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID creatorId = UUID.fromString(request.getCreatorId());
            GameEntity game = gameService.createGame(creatorId);

            respondOk(responseObserver, game.getId().toString(), "GAME_CREATED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void joinGame(Games.JoinGameRequest request,
                         StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID playerId = UUID.fromString(request.getPlayerId());
            gameService.joinGame(gameId, playerId);

            respondOk(responseObserver, gameId.toString(), "JOINED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void startRound(Games.StartRoundRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID houseId = UUID.fromString(request.getHousePlayerId());
            BigDecimal betLimit = BigDecimal.valueOf(request.getBetLimit());
            gameService.startRound(gameId, houseId, betLimit);

            respondOk(responseObserver, gameId.toString(), "ROUND_STARTED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void placeBet(Games.PlaceBetRequest request,
                         StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID playerId = UUID.fromString(request.getPlayerId());
            BigDecimal amount = BigDecimal.valueOf(request.getAmount());
            betService.placeBet(gameId, playerId, amount);

            respondOk(responseObserver, gameId.toString(), "BET_PLACED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void houseRoll(Games.RollRequest request,
                          StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID houseId = UUID.fromString(request.getPlayerId());
            GameState state = rollService.houseRoll(gameId, houseId);

            respondOk(responseObserver, gameId.toString(),
                "HOUSE_ROLLED:" + state.getHouseRoll());
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void playerRoll(Games.RollRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID playerId = UUID.fromString(request.getPlayerId());
            GameState state = rollService.playerRoll(gameId, playerId);

            respondOk(responseObserver, gameId.toString(),
                "PLAYER_ROLLED:" + state.getRoundStatus());
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void getGameState(Games.GameIdRequest request,
                             StreamObserver<Games.GameStateResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameService.getGameState(gameId)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

            var builder = Games.GameStateResponse.newBuilder()
                .setGameId(state.getGameId().toString())
                .setStatus(state.getStatus())
                .setCurrentRound(state.getCurrentRound())
                .setRoundStatus(state.getRoundStatus())
                .setHousePlayerId(
                    state.getHousePlayerId() != null ? state.getHousePlayerId().toString() : "")
                .setBetLimit(
                    state.getBetLimit() != null ? state.getBetLimit().doubleValue() : 0)
                .addAllTurnOrder(
                    state.getTurnOrder().stream().map(UUID::toString).toList())
                .setHouseRoll(state.getHouseRoll() != null ? state.getHouseRoll() : 0);

            for (var bet : state.getBets()) {
                builder.addBets(Games.BetInfo.newBuilder()
                    .setPlayerId(bet.getPlayerId().toString())
                    .setAmount(bet.getAmount().doubleValue())
                    .build());
            }
            for (var roll : state.getPlayerRolls()) {
                builder.addPlayerRolls(Games.RollInfo.newBuilder()
                    .setPlayerId(roll.getPlayerId().toString())
                    .setDiceValue(roll.getDiceValue())
                    .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void completeGame(Games.GameIdRequest request,
                             StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            gameService.completeGame(gameId);
            respondOk(responseObserver, gameId.toString(), "GAME_COMPLETED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    private void respondOk(StreamObserver<Games.GameResponse> observer,
                           String id, String message) {
        var response = Games.GameResponse.newBuilder()
            .setId(id)
            .setMessage(message)
            .build();
        observer.onNext(response);
        observer.onCompleted();
    }

    private void respondError(StreamObserver<Games.GameResponse> observer, Exception e) {
        log.error("GameController error", e);
        observer.onError(
            Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
    }
}
