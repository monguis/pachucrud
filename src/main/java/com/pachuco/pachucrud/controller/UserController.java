package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.UserService;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users;

/**
 * Maps gRPC messages to {@link UserService} calls. Exceptions propagate to
 * {@link StatusExceptionHandler}; transactions live in the service.
 */
@GrpcService
public class UserController extends UserGrpc.UserImplBase {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void getUser(Users.UserIdRequest request,
                        StreamObserver<Users.UserResponse> responseObserver) {
        UserEntity user = userService.getUser(UUID.fromString(request.getId()));
        respond(responseObserver, toResponse(user));
    }

    @Override
    public void getUserByAuthId(Users.AuthIdRequest request,
                                StreamObserver<Users.UserResponse> responseObserver) {
        respond(responseObserver, toResponse(userService.getUserByAuthId(request.getAuthId())));
    }

    @Override
    public void getUserByEmail(Users.EmailRequest request,
                               StreamObserver<Users.UserResponse> responseObserver) {
        respond(responseObserver, toResponse(userService.getUserByEmail(request.getEmail())));
    }

    @Override
    public void addPlayerUser(Users.UserRequest request,
                              StreamObserver<Users.UserResponse> responseObserver) {
        UserEntity user = userService.addPlayerUser(request.getAuthId(), request.getEmail());
        respond(responseObserver, toResponse(user));
    }

    @Override
    public void addTestPlayer(Users.UserRequest request,
                              StreamObserver<Users.UserResponse> responseObserver) {
        UserEntity user = userService.addTestPlayer(request.getUsername(), request.getNickname(),
            BigDecimal.valueOf(request.getBalance()));
        respond(responseObserver, toResponse(user));
    }

    @Override
    public void listUsersByRole(Users.RoleRequest request,
                                StreamObserver<Users.UsersResponse> responseObserver) {
        UserRole role;
        try {
            role = UserRole.valueOf(request.getRole());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role: " + request.getRole());
        }
        Users.UsersResponse.Builder response = Users.UsersResponse.newBuilder();
        for (UserEntity user : userService.listUsersByRole(role)) {
            response.addUsers(toResponse(user));
        }
        respond(responseObserver, response.build());
    }

    @Override
    public void updateUser(Users.UpdateUserRequest request,
                           StreamObserver<Users.UserResponse> responseObserver) {
        Users.UserRequest payload = request.getUser();
        UserEntity user = userService.updateUser(UUID.fromString(request.getId()),
            payload.getNickname(), payload.getUsername(), payload.getEmail());
        respond(responseObserver, toResponse(user));
    }

    @Override
    public void deleteUser(Users.UserIdRequest request,
                           StreamObserver<Users.UserResponse> responseObserver) {
        UUID id = UUID.fromString(request.getId());
        userService.deleteUser(id);
        respond(responseObserver, Users.UserResponse.newBuilder().setId(id.toString()).build());
    }

    @Override
    public void getBalance(Users.UserIdRequest request,
                           StreamObserver<Users.BalanceResponse> responseObserver) {
        respond(responseObserver, toBalanceResponse(UUID.fromString(request.getId())));
    }

    @Override
    public void deposit(Users.DepositRequest request,
                        StreamObserver<Users.BalanceResponse> responseObserver) {
        UUID userId = UUID.fromString(request.getUserId());
        userService.deposit(userId, BigDecimal.valueOf(request.getAmount()));
        respond(responseObserver, toBalanceResponse(userId));
    }

    private static <T> void respond(StreamObserver<T> observer, T response) {
        observer.onNext(response);
        observer.onCompleted();
    }

    private Users.BalanceResponse toBalanceResponse(UUID userId) {
        return Users.BalanceResponse.newBuilder()
            .setUserId(userId.toString())
            .setBalance(userService.getBalance(userId).doubleValue())
            .build();
    }

    private Users.UserResponse toResponse(UserEntity user) {
        return Users.UserResponse.newBuilder()
            .setId(user.getId().toString())
            .setAuthId(user.getAuthId())
            .setUsername(user.getUsername() != null ? user.getUsername() : "")
            .setNickname(user.getNickname() != null ? user.getNickname() : "")
            .setEmail(user.getEmail())
            .addAllRoles(user.getRoles().stream().map(Enum::name).toList())
            .setBalance(userService.getBalance(user.getId()).doubleValue())
            .build();
    }
}
