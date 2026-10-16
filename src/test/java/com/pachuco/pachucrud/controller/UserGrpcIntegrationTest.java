package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.service.RedisService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real gRPC calls against real Postgres (Flyway-migrated). Redis is a mock that never
 * holds a balance, so every balance in a response is recomputed from events, as it
 * would be after a Redis flush.
 */
@SpringBootTest(properties = {
    "spring.grpc.server.port=0",
    "spring.jpa.show-sql=false",
    "test-players.enabled=true"
})
@Testcontainers
class UserGrpcIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @MockitoBean RedisService redisService;

    @LocalGrpcPort int port;

    private ManagedChannel channel;
    private UserGrpc.UserBlockingStub users;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        users = UserGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    @Test
    void testPlayerIsCreatedAndBalanceSurvivesCacheFlush() {
        Users.UserResponse created = users.addTestPlayer(
            Users.UserRequest.newBuilder().setBalance(2500).build());

        assertEquals(List.of("PLAYER", "TEST_PLAYER"), created.getRolesList());
        assertEquals(2500.0, created.getBalance());

        Users.BalanceResponse balance = users.getBalance(
            Users.UserIdRequest.newBuilder().setId(created.getId()).build());
        assertEquals(2500.0, balance.getBalance());
    }

    @Test
    void depositIsReflectedFromEvents() {
        Users.UserResponse created = users.addTestPlayer(
            Users.UserRequest.newBuilder().setBalance(100).build());

        Users.BalanceResponse balance = users.deposit(Users.DepositRequest.newBuilder()
            .setUserId(created.getId()).setAmount(50).build());

        assertEquals(150.0, balance.getBalance());
    }

    @Test
    void unknownUserIsNotFound() {
        var e = assertThrows(StatusRuntimeException.class, () -> users.getUser(
            Users.UserIdRequest.newBuilder().setId(UUID.randomUUID().toString()).build()));

        assertEquals(Status.Code.NOT_FOUND, e.getStatus().getCode());
    }

    @Test
    void failedTransactionReportsItsRealCause() {
        // A constraint failure inside a transaction used to surface as
        // UnexpectedRollbackException plus "call already closed".
        Users.UserResponse first = users.addTestPlayer(
            Users.UserRequest.newBuilder().setUsername("taken").build());
        Users.UserResponse second = users.addTestPlayer(Users.UserRequest.getDefaultInstance());

        var e = assertThrows(StatusRuntimeException.class, () -> users.updateUser(
            Users.UpdateUserRequest.newBuilder()
                .setId(second.getId())
                .setUser(Users.UserRequest.newBuilder().setUsername(first.getUsername()))
                .build()));

        assertEquals(Status.Code.ALREADY_EXISTS, e.getStatus().getCode());
    }
}
