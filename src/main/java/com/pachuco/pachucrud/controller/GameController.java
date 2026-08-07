package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.repository.GameRepository;
import com.pachuco.pachucrud.repository.entity.GameEntity;
import com.pachuco.pachucrud.service.BetService;
import com.pachuco.pachucrud.service.GameService;
import com.pachuco.pachucrud.service.GameStageService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.RollService;
import com.pachuco.pachucrud.service.model.GameState;
import com.pachuco.pachucrud.service.model.RollResult;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import pachuco_proto.GameServiceGrpc;
import pachuco_proto.Games;

@GrpcService
public class GameController extends GameServiceGrpc.GameServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GameController.class);

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
        try {
            var games = gameRepository.findAll();
            var builder = Games.GetAllGamesResponse.newBuilder();

            for (GameEntity g : games) {
                var state = redisService.getGameState(g.getId());
                if (state.isEmpty()) {
                    continue;
                }
                if ("COMPLETED".equals(state.get().getStatus())
                        || g.getStatus() == com.pachuco.pachucrud.model.GameStatus.COMPLETED) {
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
                    .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void createGame(Games.CreateGameRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID creatorId = UUID.fromString(request.getCreatorId());
            GameEntity game = gameService.createGame(creatorId);

            respondOk(responseObserver, gameService.getGameState(game.getId()), "GAME_CREATED");
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

            respondOk(responseObserver, gameService.getGameState(gameId), "JOINED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void markPlayerReady(Games.MarkPlayerReadyRequest request,
                                StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID playerId = UUID.fromString(request.getPlayerId());
            GameState state = gameStageService.markPlayerReady(gameId, playerId);

            respondOk(responseObserver, java.util.Optional.of(state), "PLAYER_READY");
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

            respondOk(responseObserver, gameService.getGameState(gameId), "ROUND_STARTED");
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

            respondOk(responseObserver, gameService.getGameState(gameId), "BET_PLACED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void houseRoll(Games.RollRequest request,
                          StreamObserver<Games.RollResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID houseId = UUID.fromString(request.getPlayerId());
            RollResult result = rollService.houseRoll(gameId, houseId);

            var response = Games.RollResponse.newBuilder()
                .setGameId(gameId.toString())
                .setPlayerId(houseId.toString())
                .addAllDice(result.getModel().getDiceList())
                .setCombo(result.getModel().getComboName())
                .setWinner(false)
                .setMessage("HOUSE_ROLLED")
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void playerRoll(Games.RollRequest request,
                           StreamObserver<Games.RollResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            UUID playerId = UUID.fromString(request.getPlayerId());
            RollResult result = rollService.playerRoll(gameId, playerId);

            var response = Games.RollResponse.newBuilder()
                .setGameId(gameId.toString())
                .setPlayerId(playerId.toString())
                .addAllDice(result.getModel().getDiceList())
                .setCombo(result.getModel().getComboName())
                .setWinner(result.isWinner())
                .setMessage("PLAYER_ROLLED:" + result.getOutcome())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToEnoughPlayers(Games.GameIdRequest request,
                                       StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.advanceToEnoughPlayers(gameId);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_ENOUGH_PLAYERS");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToGameStart(Games.AdvanceToGameStartRequest request,
                                   StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            List<UUID> playerOrder = request.getPlayerOrderList().stream()
                .map(UUID::fromString)
                .toList();
            GameState state = gameStageService.advanceToGameStart(gameId, playerOrder);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_GAME_START");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToPlayersBetSetting(Games.GameIdRequest request,
                                           StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.advanceToPlayersBetSetting(gameId);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_PLAYERS_BET_SETTING");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToBankThrow(Games.GameIdRequest request,
                                   StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.advanceToBankThrow(gameId);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_BANK_THROW");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToPlayersThrow(Games.GameIdRequest request,
                                       StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.advanceToPlayersThrow(gameId);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_PLAYERS_THROW");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToRoundCompleted(Games.GameIdRequest request,
                                        StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.advanceToRoundCompleted(gameId);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_ROUND_COMPLETED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void advanceToNextRound(Games.GameIdRequest request,
                                   StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.advanceToNextRound(gameId);

            respondOk(responseObserver, java.util.Optional.of(state), "ADVANCED_TO_NEXT_ROUND");
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

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getCurrentTurn(Games.GameIdRequest request,
                               StreamObserver<Games.CurrentTurnResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            GameState state = gameStageService.getCurrentTurn(gameId);

            int turnIdx = state.getCurrentTurn();
            List<UUID> order = state.getTurnOrder();
            String currentPlayerId = turnIdx < order.size() ? order.get(turnIdx).toString() : "";

            var response = Games.CurrentTurnResponse.newBuilder()
                .setGameId(gameId.toString())
                .setCurrentTurn(turnIdx)
                .setCurrentPlayerId(currentPlayerId)
                .build();

            responseObserver.onNext(response);
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

            respondOk(responseObserver, gameService.getGameState(gameId), "GAME_COMPLETED");
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    @Override
    public void deleteGame(Games.GameIdRequest request,
                           StreamObserver<Games.GameResponse> responseObserver) {
        try {
            UUID gameId = UUID.fromString(request.getGameId());
            gameService.deleteGame(gameId);

            Games.GameResponse response = Games.GameResponse.newBuilder()
                .setId(gameId.toString())
                .setStatus("DELETED")
                .setMessage("GAME_DELETED")
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            respondError(responseObserver, e);
        }
    }

    private void respondOk(StreamObserver<Games.GameResponse> observer,
                           java.util.Optional<GameState> stateOpt, String message) {
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

        observer.onNext(builder.build());
        observer.onCompleted();
    }

    private <T> void respondError(StreamObserver<T> observer, Exception e) {
        log.error("GameController error", e);
        observer.onError(
            Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
    }
}
