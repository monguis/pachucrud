package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users;

@GrpcService
public class UserController extends UserGrpc.UserImplBase {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserRepository userRepository;
    private final RedisService redisService;
    private final EventService eventService;

    public UserController(UserRepository userRepository,
                          RedisService redisService,
                          EventService eventService) {
        this.userRepository = userRepository;
        this.redisService = redisService;
        this.eventService = eventService;
    }

    @Override
    @Transactional(readOnly = true)
    public void getUser(Users.UserIdRequest request,
                        StreamObserver<Users.UserResponse> responseObserver) {
        try {
            UUID userId = UUID.fromString(request.getId());
            UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

            responseObserver.onNext(toResponse(user));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void getUserByAuthId(Users.AuthIdRequest request,
                                StreamObserver<Users.UserResponse> responseObserver) {
        try {
            UserEntity user = userRepository.findByAuthId(request.getAuthId())
                .orElseThrow(() -> new IllegalArgumentException("User not found for authId"));

            responseObserver.onNext(toResponse(user));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void getUserByEmail(Users.EmailRequest request,
                               StreamObserver<Users.UserResponse> responseObserver) {
        try {
            UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found for email"));

            responseObserver.onNext(toResponse(user));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional
    public void addPlayerUser(Users.UserRequest request,
                              StreamObserver<Users.UserResponse> responseObserver) {
        try {
            UserEntity entity = new UserEntity();
            entity.setAuthId(request.getAuthId());
            entity.setEmail(request.getEmail());
            entity.setRoles(List.of(UserRole.PLAYER));
            entity = userRepository.save(entity);

            redisService.setBalance(entity.getId(), BigDecimal.ZERO);

            responseObserver.onNext(toResponse(entity));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional
    public void updateUser(Users.UpdateUserRequest request,
                           StreamObserver<Users.UserResponse> responseObserver) {
        try {
            UUID id = UUID.fromString(request.getId());
            Users.UserRequest payload = request.getUser();

            userRepository.findByEmail(payload.getEmail())
                .filter(u -> !u.getId().equals(id))
                .ifPresent(u -> { throw new IllegalArgumentException("Email already in use"); });

            UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

            if (!payload.getNickname().isEmpty()) user.setNickname(payload.getNickname());
            if (!payload.getUsername().isEmpty()) user.setUsername(payload.getUsername());
            if (!payload.getEmail().isEmpty()) user.setEmail(payload.getEmail());
            userRepository.save(user);

            responseObserver.onNext(toResponse(user));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional
    public void deleteUser(Users.UserIdRequest request,
                           StreamObserver<Users.UserResponse> responseObserver) {
        try {
            UUID id = UUID.fromString(request.getId());
            userRepository.deleteById(id);

            responseObserver.onNext(Users.UserResponse.newBuilder()
                .setId(id.toString()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getBalance(Users.UserIdRequest request,
                           StreamObserver<Users.BalanceResponse> responseObserver) {
        try {
            UUID userId = UUID.fromString(request.getId());
            BigDecimal balance = redisService.getBalance(userId).orElseGet(() -> {
                BigDecimal computed = eventService.computeUserBalance(userId);
                redisService.setBalance(userId, computed);
                return computed;
            });

            responseObserver.onNext(Users.BalanceResponse.newBuilder()
                .setUserId(userId.toString())
                .setBalance(balance.doubleValue())
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    @Transactional
    public void deposit(Users.DepositRequest request,
                        StreamObserver<Users.BalanceResponse> responseObserver) {
        try {
            UUID userId = UUID.fromString(request.getUserId());
            BigDecimal amount = BigDecimal.valueOf(request.getAmount());

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Deposit amount must be positive");
            }

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("amount", amount);
            eventData.put("type", "deposit");

            eventService.writeEvent(null, com.pachuco.pachucrud.model.EventType.DEPOSIT,
                userId, eventData);

            BigDecimal current = redisService.getBalance(userId).orElse(BigDecimal.ZERO);
            BigDecimal newBalance = current.add(amount);
            redisService.setBalance(userId, newBalance);

            responseObserver.onNext(Users.BalanceResponse.newBuilder()
                .setUserId(userId.toString())
                .setBalance(newBalance.doubleValue())
                .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private Users.UserResponse toResponse(UserEntity user) {
        return Users.UserResponse.newBuilder()
            .setId(user.getId().toString())
            .setAuthId(user.getAuthId())
            .setUsername(user.getUsername() != null ? user.getUsername() : "")
            .setNickname(user.getNickname() != null ? user.getNickname() : "")
            .setEmail(user.getEmail())
            .addAllRoles(user.getRoles().stream()
                .map(r -> r.name().toLowerCase()).collect(Collectors.toList()))
            .build();
    }
}
