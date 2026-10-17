package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.GameService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.RollResult;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;
import pachuco_proto.GameServiceGrpc;
import pachuco_proto.Games;

/**
 * Maps gRPC messages to the game services. Exceptions propagate to
 * {@link StatusExceptionHandler}; transactions live in the services.
 */
@GrpcService
public class GameController extends GameServiceGrpc.GameServiceImplBase {

    private final GameService gameService;
    private final GameStageService gameStageService;
    private final BetService betService;
    private final RollService rollService;
    private final RedisService redisService;
    private final GameRepository gameRepository;

    public GameController(GameService gameService, GameStageService gameStageService,
                          BetService betService, RollService rollService,
                          RedisService redisService, GameRepository gameRepository) {
        this.gameService = gameService;
        this.gameStageService = gameStageService;
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
            if (state.isEmpty()) {
                continue;
            }
            if ("COMPLETED".equals(state.get().getStatus())
                    || "CANCELLED".equals(state.get().getStatus())
                    || g.getStatus() == com.pachuco.pachucrud.model.GameStatus.COMPLETED
                    || g.getStatus() == com.pachuco.pachucrud.model.GameStatus.CANCELLED) {
                continue;
            }
            int totalPlayers = state.get().getTurnOrder().size();
            BigDecimal betLimit = state.get().getBetLimit() != null
                ? state.get().getBetLimit() : BigDecimal.ZERO;

            builder.addGames(Games.GameMessage.newBuilder()
                .setId(g.getId().toString())
                .setStatus(state.get().getStatus())
                .setRoundStatus(state.get().getRoundStatus())
                .setBetLimit(betLimit.intValue())
                .setTotalPlayers(totalPlayers)
                .addAllTurnOrder(state.get().getTurnOrder().stream().map(UUID::toString).toList())
                .build());
        }

        respond(responseObserver, builder.build());
    }

    @Override
    public void createGame(Games.CreateGameRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        UUID creatorId = UUID.fromString(request.getCreatorId());
        GameEntity game = gameService.createGame(creatorId);

        respondOk(responseObserver, gameService.getGameState(game.getId()), "GAME_CREATED");
    }

    @Override
    public void joinGame(Games.JoinGameRequest request,
                         StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        UUID playerId = UUID.fromString(request.getPlayerId());
        gameService.joinGame(gameId, playerId);

        respondOk(responseObserver, gameService.getGameState(gameId), "JOINED");
    }

    @Override
    public void markPlayerReady(Games.MarkPlayerReadyRequest request,
                                StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        UUID playerId = UUID.fromString(request.getPlayerId());
        GameState state = gameStageService.markPlayerReady(gameId, playerId);

        respondOk(responseObserver, Optional.of(state), "PLAYER_READY");
    }

    @Override
    public void startRound(Games.StartRoundRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        UUID houseId = UUID.fromString(request.getHousePlayerId());
        BigDecimal betLimit = BigDecimal.valueOf(request.getBetLimit());
        gameService.startRound(gameId, houseId, betLimit);

        respondOk(responseObserver, gameService.getGameState(gameId), "ROUND_STARTED");
    }

    @Override
    public void placeBet(Games.PlaceBetRequest request,
                         StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        UUID playerId = UUID.fromString(request.getPlayerId());
        BigDecimal amount = BigDecimal.valueOf(request.getAmount());
        betService.placeBet(gameId, playerId, amount);

        respondOk(responseObserver, gameService.getGameState(gameId), "BET_PLACED");
    }

    @Override
    public void houseRoll(Games.RollRequest request,
                          StreamObserver<Games.RollResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        UUID houseId = UUID.fromString(request.getPlayerId());
        RollResult result = rollService.houseRoll(gameId, houseId);

        respond(responseObserver, Games.RollResponse.newBuilder()
            .setGameId(gameId.toString())
            .setPlayerId(houseId.toString())
            .addAllDice(result.getModel().getDiceList())
            .setCombo(result.getModel().getComboName())
            .setWinner(false)
            .setMessage("HOUSE_ROLLED")
            .build());
    }

    @Override
    public void playerRoll(Games.RollRequest request,
                           StreamObserver<Games.RollResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        UUID playerId = UUID.fromString(request.getPlayerId());
        RollResult result = rollService.playerRoll(gameId, playerId);

        respond(responseObserver, Games.RollResponse.newBuilder()
            .setGameId(gameId.toString())
            .setPlayerId(playerId.toString())
            .addAllDice(result.getModel().getDiceList())
            .setCombo(result.getModel().getComboName())
            .setWinner(result.isWinner())
            .setMessage("PLAYER_ROLLED:" + result.getOutcome())
            .build());
    }

    @Override
    public void advanceToEnoughPlayers(Games.GameIdRequest request,
                                       StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.advanceToEnoughPlayers(gameId);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_ENOUGH_PLAYERS");
    }

    @Override
    public void advanceToGameStart(Games.AdvanceToGameStartRequest request,
                                   StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        List<UUID> playerOrder = request.getPlayerOrderList().stream()
            .map(UUID::fromString)
            .toList();
        GameState state = gameStageService.advanceToGameStart(gameId, playerOrder);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_GAME_START");
    }

    @Override
    public void advanceToPlayersBetSetting(Games.GameIdRequest request,
                                           StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.advanceToPlayersBetSetting(gameId);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_PLAYERS_BET_SETTING");
    }

    @Override
    public void advanceToBankThrow(Games.GameIdRequest request,
                                   StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.advanceToBankThrow(gameId);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_BANK_THROW");
    }

    @Override
    public void advanceToPlayersThrow(Games.GameIdRequest request,
                                       StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.advanceToPlayersThrow(gameId);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_PLAYERS_THROW");
    }

    @Override
    public void advanceToRoundCompleted(Games.GameIdRequest request,
                                        StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.advanceToRoundCompleted(gameId);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_ROUND_COMPLETED");
    }

    @Override
    public void advanceToNextRound(Games.GameIdRequest request,
                                   StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.advanceToNextRound(gameId);

        respondOk(responseObserver, Optional.of(state), "ADVANCED_TO_NEXT_ROUND");
    }

    @Override
    public void getGameState(Games.GameIdRequest request,
                             StreamObserver<Games.GameStateResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameService.getGameState(gameId)
            .orElseThrow(() -> new NotFoundException("Game not found: " + gameId));

        var builder = Games.GameStateResponse.newBuilder()
            .setGameId(state.getGameId().toString())
            .setStatus(state.getStatus())
            .setCurrentRound(state.getCurrentRound())
            .setRoundStatus(state.getRoundStatus())
            .setHousePlayerId(
                state.getHousePlayerId() != null ? state.getHousePlayerId().toString() : "")
            .setBetLimit(
                state.getBetLimit() != null ? state.getBetLimit().doubleValue() : 0)
            .setMaxPlayers(state.getMaxPlayers())
            .setRoundNumber(state.getCurrentRound())
            .addAllTurnOrder(
                state.getTurnOrder().stream().map(UUID::toString).toList())
            .addAllHouseDice(state.getHouseDice())
            .addAllWaitingPlayers(
                state.getWaitingPlayers().stream().map(UUID::toString).toList())
            .addAllReadyPlayers(
                state.getReadyPlayers().stream().map(UUID::toString).toList())
            .setCurrentTurn(state.getCurrentTurn())
            .setNeedsShuffling(state.isNeedsShuffling());

        for (var bet : state.getBets()) {
            builder.addBets(Games.BetInfo.newBuilder()
                .setPlayerId(bet.getPlayerId().toString())
                .setAmount(bet.getAmount().doubleValue())
                .build());
        }
        for (var roll : state.getPlayerRolls()) {
            builder.addPlayerRolls(Games.RollInfo.newBuilder()
                .setPlayerId(roll.getPlayerId().toString())
                .addAllDice(roll.getDice())
                .setCombo(roll.getCombo() != null ? roll.getCombo() : "")
                .setOutcome(roll.getOutcome() != null ? roll.getOutcome() : "")
                .build());
        }

        respond(responseObserver, builder.build());
    }

    @Override
    public void getCurrentTurn(Games.GameIdRequest request,
                               StreamObserver<Games.CurrentTurnResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.getCurrentTurn(gameId);

        int turnIdx = state.getCurrentTurn();
        List<UUID> order = state.getTurnOrder();
        String currentPlayerId = turnIdx < order.size() ? order.get(turnIdx).toString() : "";

        respond(responseObserver, Games.CurrentTurnResponse.newBuilder()
            .setGameId(gameId.toString())
            .setCurrentTurn(turnIdx)
            .setCurrentPlayerId(currentPlayerId)
            .build());
    }

    @Override
    public void completeGame(Games.GameIdRequest request,
                             StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        gameService.completeGame(gameId);

        respondOk(responseObserver, gameService.getGameState(gameId), "GAME_COMPLETED");
    }

    @Override
    public void failLobby(Games.GameIdRequest request,
                          StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        GameState state = gameStageService.failLobby(gameId);

        respondOk(responseObserver, Optional.of(state), "LOBBY_FAILED");
    }

    @Override
    public void deleteGame(Games.GameIdRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        UUID gameId = UUID.fromString(request.getGameId());
        gameService.deleteGame(gameId);

        respond(responseObserver, Games.GameResponse.newBuilder()
            .setId(gameId.toString())
            .setStatus("DELETED")
            .setMessage("GAME_DELETED")
            .build());
    }

    private static <T> void respond(StreamObserver<T> observer, T response) {
        observer.onNext(response);
        observer.onCompleted();
    }

    private void respondOk(StreamObserver<Games.GameResponse> observer,
                           Optional<GameState> stateOpt, String message) {
        var builder = Games.GameResponse.newBuilder().setMessage(message);

        stateOpt.ifPresent(state -> {
            builder.setId(state.getGameId().toString())
                .setStatus(state.getStatus())
                .setCurrentRound(state.getCurrentRound());
            if (state.getHousePlayerId() != null) {
                builder.setHousePlayerId(state.getHousePlayerId().toString());
            }
            builder.addAllTurnOrder(state.getTurnOrder().stream().map(UUID::toString).toList());
        });

        respond(observer, builder.build());
    }
}
