package com.pachuco.pachucrud.config;

import com.pachuco.pachucrud.service.RedisService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Bots seeded at startup, observed over gRPC against real Postgres and Redis. */
@SpringBootTest(properties = {
    "spring.grpc.server.port=0",
    "spring.jpa.show-sql=false",
    "bots.seed.enabled=true",
    "bots.seed.count=3",
    "bots.seed.start-index=1",
    "bots.seed.balance=1000.0"
})
@Testcontainers
class BotSeedingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired RedisService redisService;
    @Autowired BotSeeder seeder;

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
    void seededBotsAreFundedAndCacheMatchesEvents() {
        for (String bot : new String[] {"bot|001", "bot|002", "bot|003"}) {
            String id = botId(bot);
            assertEquals(1000.0, balance(id));

            redisService.deleteBalance(UUID.fromString(id));
            assertEquals(1000.0, balance(id), "seeded funds must be recorded as a Deposit");
        }
    }

    @Test
    void reseedingLeavesExistingBotsUntouched() throws Exception {
        String id = botId("bot|002");
        double before = balance(id);

        seeder.run(new DefaultApplicationArguments());

        assertEquals(before, balance(id));
        redisService.deleteBalance(UUID.fromString(id));
        assertEquals(before, balance(id));
    }

    private String botId(String authId) {
        return users.getUserByAuthId(Users.AuthIdRequest.newBuilder().setAuthId(authId).build()).getId();
    }

    private double balance(String userId) {
        return users.getBalance(Users.UserIdRequest.newBuilder().setId(userId).build()).getBalance();
    }
}
