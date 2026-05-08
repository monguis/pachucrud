package com.pachuco.pachucrud.controller;

import org.springframework.grpc.server.service.GrpcService;

import io.grpc.stub.StreamObserver;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users.UserIdRequest;
import pachuco_proto.Users.UserRequest;
import pachuco_proto.Users.UserResponse;

import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@GrpcService
public class UserController extends UserGrpc.UserImplBase{
    Logger logger = LoggerFactory.getLogger(UserController.class);
    public void getUser(UserIdRequest request, StreamObserver<UserResponse> responseObserver) {

        String userId = request.getId();

        logger.trace("Log level: TRACE");
        logger.info("Log level: INFO");
        logger.debug("Log level: DEBUG");
        logger.error("Log level: ERROR");
        logger.warn("Log level: WARN");

        UUID uuid = UUID.randomUUID();

        String uuidString = uuid.toString();

        UserResponse response = UserResponse.newBuilder()
                .setId(uuidString)
                .setNickname("ExampleNick")
                .setEmail("test@example.com")
                .addAllRoles(Arrays.asList("player", "admin"))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addUser(UserRequest request, StreamObserver<UserResponse> responseObserver) {
        // Logic to save user...

        UserResponse response = UserResponse.newBuilder()
                .setUsername(request.getUsername())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
