package com.pachuco.pachucrud.controller;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.service.GrpcService;

import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.sevice.ThrowService;

import io.grpc.stub.StreamObserver;
import pachuco_proto.DiceThrowGrpc.DiceThrowImplBase;
import pachuco_proto.DiceThrows.ThrowRequest;
import pachuco_proto.DiceThrows.ThrowResponse;

import java.util.Arrays;

import org.slf4j.Logger;

@GrpcService
public class ThrowController extends DiceThrowImplBase {
    Logger logger = LoggerFactory.getLogger(ThrowController.class);

    private ThrowService throwService;

    @Autowired
    public ThrowController(ThrowService throwService) {
        this.throwService = throwService;
    }

    @Override
    public void postThrow(ThrowRequest request, StreamObserver<ThrowResponse> responseObserver) {
        logger.info("Posting Throw (MOCK)", request.getGameId(), request.getPlayerId());

        ThrowModel resp;
        try {
            resp = this.throwService.generaThrowModel();
            ThrowResponse response = ThrowResponse.newBuilder()
                    .setCombo(resp.getCombo().getValue())
                    .addAllDice(Arrays.asList(resp.getDice()))
                    .setMostRepeatedDie(resp.getMostRepeatedDice())
                    .setSecondMostRepeatedDie(resp.getSecondMostRepeatedDice())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            logger.info("Posting Throw success (MOCK)", request.getGameId(), request.getPlayerId());
        } catch (Exception e) {
            logger.error("Valio gaver" + e);
        }

    }
}
