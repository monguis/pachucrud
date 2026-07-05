package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.model.UserRole;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import pachuco_proto.UserGrpc.UserImplBase;
import pachuco_proto.Users.AuthIdRequest;
import pachuco_proto.Users.UpdateUserRequest;
import pachuco_proto.Users.UserIdRequest;
import pachuco_proto.Users.UserRequest;
import pachuco_proto.Users.UserResponse;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class UserController extends UserImplBase {
    Logger logger = LoggerFactory.getLogger(UserController.class);
    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void getUser(UserIdRequest request, StreamObserver<UserResponse> responseObserver) {
        String userIdString = request.getId();

        UUID userId = UUID.fromString(userIdString);
        UserEntity user = userRepository.findById(userId).orElse(null);

        if (user == null) {
            responseObserver.onError(
                Status.NOT_FOUND
                    .withDescription("User not found for userId: " + userIdString)
                    .asRuntimeException()
            );
            return;
        }

        UserResponse response = UserResponse.newBuilder()
                .setId(user.getId().toString())
                .setAuthId(user.getAuthId())
                .setUsername(user.getUsername())
                .setNickname(user.getNickname() != null ? user.getNickname() : "")
                .setEmail(user.getEmail())
                .addAllRoles(user.getRoles().stream().map(r -> r.name().toLowerCase()).collect(Collectors.toList()))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();

    }


    @Override
    @Transactional(readOnly = true)
    public void getUserByAuthId(AuthIdRequest request, StreamObserver<UserResponse> responseObserver) {
        String authId = request.getAuthId();

        UserEntity user = userRepository.findByAuthId(authId).orElse(null);

        if (user == null) {
            responseObserver.onError(
                Status.NOT_FOUND
                    .withDescription("User not found for authId: " + authId)
                    .asRuntimeException()
            );
            return;
        }

        UserResponse response = UserResponse.newBuilder()
                .setId(user.getId().toString())
                .setAuthId(user.getAuthId())
                .setUsername(user.getUsername())
                .setNickname(user.getNickname() != null ? user.getNickname() : "")
                .setEmail(user.getEmail())
                .addAllRoles(user.getRoles().stream().map(r -> r.name().toUpperCase()).collect(Collectors.toList()))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addPlayerUser(UserRequest request, StreamObserver<UserResponse> responseObserver) {
        UserEntity entity = new UserEntity();
        entity.setAuthId(request.getAuthId());
        entity.setEmail(request.getEmail());
        entity.setRoles(List.of(UserRole.PLAYER));

        entity = userRepository.save(entity);

        UserResponse response = UserResponse.newBuilder()
                .setId(entity.getId().toString())
                .setAuthId(entity.getAuthId())
                .setEmail(entity.getEmail())
                .addAllRoles(entity.getRoles().stream().map(r -> r.name().toUpperCase()).collect(Collectors.toList()))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    @Transactional(readOnly = false)
    public void updateUser(UpdateUserRequest request, StreamObserver<UserResponse> responseObserver) {
        UUID id = UUID.fromString(request.getId());
        pachuco_proto.Users.UserRequest payload = request.getUser();

        int updated = userRepository.updateUserFields(id, payload.getNickname(), payload.getUsername(), payload.getEmail());

        if (updated == 0) {
            responseObserver.onError(
                Status.NOT_FOUND
                    .withDescription("User not found for id: " + id)
                    .asRuntimeException()
            );
            return;
        }

        UserEntity user = userRepository.findById(id).orElseThrow();

        UserResponse response = UserResponse.newBuilder()
                .setId(user.getId().toString())
                .setAuthId(user.getAuthId())
                .setUsername(user.getUsername())
                .setNickname(user.getNickname() != null ? user.getNickname() : "")
                .setEmail(user.getEmail())
                .addAllRoles(user.getRoles().stream().map(r -> r.name().toUpperCase()).collect(Collectors.toList()))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void deleteUser(UserIdRequest request, StreamObserver<UserResponse> responseObserver) {
        UUID id = UUID.fromString(request.getId());

        int deleted = userRepository.deleteUserById(id);

        if (deleted == 0) {
            responseObserver.onError(
                Status.NOT_FOUND
                    .withDescription("User not found for id: " + id)
                    .asRuntimeException()
            );
            return;
        }

        UserResponse response = UserResponse.newBuilder()
                .setId(id.toString())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
