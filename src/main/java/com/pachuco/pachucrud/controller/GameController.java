package com.pachuco.pachucrud.controller;

import org.springframework.grpc.server.service.GrpcService;
import io.grpc.stub.StreamObserver;
import pachuco_proto.GameServiceGrpc;
import pachuco_proto.Games.EmptyRequest;
import pachuco_proto.Games.GameMessage;
import pachuco_proto.Games.GetAllGamesResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@GrpcService
public class GameController extends GameServiceGrpc.GameServiceImplBase {
    Logger logger = LoggerFactory.getLogger(GameController.class);

    @Override
    public void getAllGames(EmptyRequest request, StreamObserver<GetAllGamesResponse> responseObserver) {
        logger.info("Fetching all available games");

        GameMessage game1 = GameMessage.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setStatus("open")
                .setBetLimit(20)
                .setTotalPlayers(8)
                .build();

        GameMessage game2 = GameMessage.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setStatus("open")
                .setBetLimit(50)
                .setTotalPlayers(6)
                .build();

        GetAllGamesResponse response = GetAllGamesResponse.newBuilder()
                .addGames(game1)
                .addGames(game2)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        logger.info("Returning {} games", response.getGamesCount());
    }
}
