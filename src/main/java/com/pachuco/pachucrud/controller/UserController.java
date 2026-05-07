package com.pachuco.pachucrud.controller;

import org.springframework.grpc.server.service.GrpcService;

import io.grpc.stub.StreamObserver;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users.UserIdRequest;
// Check your target folder to see if these are inside an outer class or not
import pachuco_proto.Users.UserRequest;
import pachuco_proto.Users.UserResponse;

@GrpcService // This tells Spring to host this service on the gRPC port (default 9090)
public class UserController extends UserGrpc.UserImplBase{
    public void getUser(UserIdRequest request, StreamObserver<UserResponse> responseObserver) {
        // Accessing the ID from the request
        String userId = request.getId();

        // Building the response
        UserResponse response = UserResponse.newBuilder()
                .setId(userId)
                .setNickname("ExampleNick")
                .setEmail("test@example.com")
                .setBalance(100.0f)
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
