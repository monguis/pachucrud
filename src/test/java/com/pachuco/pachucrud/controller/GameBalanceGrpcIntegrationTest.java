package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.service.RedisService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pachuco_proto.GameServiceGrpc;
import pachuco_proto.Games;
import pachuco_proto.UserGrpc;
import pachuco_proto.Users;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plays game rounds over real gRPC against real Postgres and real Redis, checking
 * that every participant's cached balance equals the balance recomputed from events
 * (issue #1: events are the source of truth, Redis is a rebuildable cache).
 */
@SpringBootTest(properties = {
    "spring.grpc.server.port=0",
    "spring.jpa.show-sql=false",
    "test-players.enabled=true"
})
@Testcontainers
class GameBalanceGrpcIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired RedisService redisService;

    @LocalGrpcPort int port;

    private ManagedChannel channel;
    private UserGrpc.UserBlockingStub users;
    private GameServiceGrpc.GameServiceBlockingStub games;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        users = UserGrpc.newBlockingStub(channel);
        games = GameServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    @Test
    void cachedBalancesMatchEventsAfterBetting() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);

        String game = gameAtBetting(house, alice, bob);
        bet(game, alice, 10);
        bet(game, bob, 20);

        assertCacheMatchesEvents(house, alice, bob);
        assertEquals(90.0, balance(alice));
        assertEquals(80.0, balance(bob));
    }

    @Test
    void concurrentBetsInDifferentGamesAreAllDeducted() throws Exception {
        int gameCount = 8;
        String alice = player(1000);
        List<String> gameIds = new ArrayList<>();
        for (int i = 0; i < gameCount; i++) {
            gameIds.add(gameAtBetting(player(1000), alice));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(gameCount);
        try {
            List<Future<?>> bets = new ArrayList<>();
            for (String game : gameIds) {
                bets.add(pool.submit(() -> {
                    start.await();
                    bet(game, alice, 10);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : bets) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1000.0 - gameCount * 10, balance(alice));
        assertCacheMatchesEvents(alice);
    }

    @Test
    void settledRoundMatchesEventsAndIsZeroSum() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String carol = player(100);

        String game = gameAtBetting(house, alice, bob, carol);
        bet(game, alice, 10);
        bet(game, bob, 20);
        bet(game, carol, 30);
        rollRound(game, house);

        // Dice are random: the invariants must hold whatever the outcomes.
        assertCacheMatchesEvents(house, alice, bob, carol);
        assertEquals(1300.0, balance(house) + balance(alice) + balance(bob) + balance(carol));
    }

    @Test
    void houseBalanceSurvivesCacheFlush() {
        String house = player(1000);
        String alice = player(100);

        String game = gameAtBetting(house, alice);
        bet(game, alice, 25);
        rollRound(game, house);

        double before = balance(house);
        redisService.deleteBalance(UUID.fromString(house));

        assertEquals(before, balance(house));
    }

    // ---- harness ----

    /** House rolls, then every Player who bet rolls in turn until the round completes. */
    private void rollRound(String game, String house) {
        games.advanceToBankThrow(gameId(game));
        games.houseRoll(Games.RollRequest.newBuilder().setGameId(game).setPlayerId(house).build());
        games.advanceToPlayersThrow(gameId(game));
        while (!"COMPLETED".equals(games.getGameState(gameId(game)).getRoundStatus())) {
            String next = games.getCurrentTurn(gameId(game)).getCurrentPlayerId();
            games.playerRoll(Games.RollRequest.newBuilder().setGameId(game).setPlayerId(next).build());
        }
    }

    private String player(double startingBalance) {
        return users.addTestPlayer(Users.UserRequest.newBuilder()
            .setBalance((float) startingBalance).build()).getId();
    }

    /** Creates a game with {@code house} as the House and drives it to the betting stage. */
    private String gameAtBetting(String house, String... players) {
        String game = games.createGame(Games.CreateGameRequest.newBuilder()
            .setCreatorId(house).build()).getId();
        for (String p : players) {
            games.joinGame(Games.JoinGameRequest.newBuilder().setGameId(game).setPlayerId(p).build());
        }
        games.advanceToEnoughPlayers(gameId(game));
        games.advanceToGameStart(Games.AdvanceToGameStartRequest.newBuilder()
            .setGameId(game).addPlayerOrder(house).addAllPlayerOrder(List.of(players)).build());
        games.startRound(Games.StartRoundRequest.newBuilder()
            .setGameId(game).setHousePlayerId(house).setBetLimit(50).build());
        games.advanceToPlayersBetSetting(gameId(game));
        return game;
    }

    private void bet(String game, String player, double amount) {
        games.placeBet(Games.PlaceBetRequest.newBuilder()
            .setGameId(game).setPlayerId(player).setAmount(amount).build());
    }

    private double balance(String userId) {
        return users.getBalance(Users.UserIdRequest.newBuilder().setId(userId).build()).getBalance();
    }

    /** The cached balance must equal what a cache miss would recompute from events. */
    private void assertCacheMatchesEvents(String... userIds) {
        for (String id : userIds) {
            double cached = balance(id);
            redisService.deleteBalance(UUID.fromString(id));
            double recomputed = balance(id);
            assertEquals(recomputed, cached, "cached balance drifted from events for " + id);
        }
    }

    private static Games.GameIdRequest gameId(String game) {
        return Games.GameIdRequest.newBuilder().setGameId(game).build();
    }
}
