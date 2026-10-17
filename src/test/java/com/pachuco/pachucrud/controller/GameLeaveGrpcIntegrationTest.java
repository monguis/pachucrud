package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.service.RedisService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.UUID;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Players Leave a game over real gRPC against real Postgres and real Redis: they give
 * up their seat, and the table stops waiting for them.
 */
@SpringBootTest(properties = {
    "spring.grpc.server.port=0",
    "spring.jpa.show-sql=false",
    "test-players.enabled=true"
})
@Testcontainers
class GameLeaveGrpcIntegrationTest {

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
    void aJoinedPlayerWhoLeavesTheLobbyLosesTheirSeat() {
        String creator = player(1000);
        String alice = player(100);
        String game = createGame(creator, alice);

        leave(game, alice);

        assertFalse(state(game).getWaitingPlayersList().contains(alice));
    }

    @Test
    void aReadyPlayerWhoLeavesALaterLobbyLosesTheirPlaceInTheRotation() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        bet(game, alice, 10);
        bet(game, bob, 10);
        rollRound(game, house);
        games.advanceToNextRound(gameId(game));
        markReady(game, alice);

        leave(game, alice);

        Games.GameStateResponse after = state(game);
        assertFalse(after.getReadyPlayersList().contains(alice));
        assertFalse(after.getTurnOrderList().contains(alice));
    }

    @Test
    void aLeaveSurvivesRebuildingTheGameFromEvents() {
        String creator = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = createGame(creator, alice, bob);

        leave(game, alice);
        redisService.deleteGameState(UUID.fromString(game));

        Games.GameStateResponse rebuilt = state(game);
        assertFalse(rebuilt.getWaitingPlayersList().contains(alice));
        assertEquals(List.of(creator, bob), rebuilt.getWaitingPlayersList());
    }

    @Test
    void leavingAGameYouAreNotSeatedInChangesNothing() {
        String creator = player(1000);
        String alice = player(100);
        String stranger = player(100);
        String game = createGame(creator, alice);

        leave(game, stranger);
        leave(game, alice);
        leave(game, alice);
        redisService.deleteGameState(UUID.fromString(game));

        assertEquals(List.of(creator), state(game).getWaitingPlayersList());
    }

    @Test
    void aPlayerWithoutABetWhoLeavesMidRoundDoesNotMoveTheTurn() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String carol = player(100);
        String game = gameAtBetting(house, alice, bob, carol);
        bet(game, bob, 10);
        bet(game, carol, 10);
        houseRolls(game, house);
        assertEquals(bob, currentPlayer(game));

        leave(game, alice);

        assertEquals(List.of(house, bob, carol), state(game).getTurnOrderList());
        assertEquals(bob, currentPlayer(game));
    }

    @Test
    void aPlayerWhoLeavesTheRoundSummaryIsNotInTheNextLobby() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        bet(game, alice, 10);
        bet(game, bob, 10);
        rollRound(game, house);

        leave(game, bob);
        games.advanceToNextRound(gameId(game));

        Games.GameStateResponse lobby = state(game);
        assertFalse(lobby.getTurnOrderList().contains(bob));
        assertFalse(lobby.getWaitingPlayersList().contains(bob));
    }

    @Test
    void aPlayerWhoComesBackJoinsAtTheEndOfTheRotation() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);

        leave(game, alice);
        join(game, alice);

        assertEquals(List.of(house, bob, alice), state(game).getTurnOrderList());
    }

    @Test
    void aBettorWhoLeavesBeforeRollingForfeitsAsAPachuco() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        bet(game, alice, 10);

        leave(game, alice);

        assertEquals(80.0, balance(alice));
        assertEquals(1020.0, balance(house));
        assertCacheMatchesEvents(house, alice);
    }

    @Test
    void aForfeitShowsWithoutDiceAndSurvivesRebuildingTheGame() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        bet(game, alice, 10);

        leave(game, alice);
        redisService.deleteGameState(UUID.fromString(game));

        Games.RollInfo forfeit = state(game).getPlayerRollsList().stream()
            .filter(r -> r.getPlayerId().equals(alice))
            .findFirst().orElseThrow();
        assertTrue(forfeit.getForfeit());
        assertEquals("lose_double", forfeit.getOutcome());
        assertTrue(forfeit.getDiceList().isEmpty());
    }

    @Test
    void theBettorWhoseTurnItIsLeavingPassesTheTurnToTheNextBettor() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String carol = player(100);
        String game = gameAtBetting(house, alice, bob, carol);
        bet(game, alice, 10);
        bet(game, carol, 10);
        houseRolls(game, house);
        assertEquals(alice, currentPlayer(game));

        leave(game, alice);

        assertEquals(carol, currentPlayer(game));
    }

    @Test
    void theLastBettorLeavingCompletesTheRound() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        bet(game, alice, 10);
        bet(game, bob, 10);
        houseRolls(game, house);
        games.playerRoll(Games.RollRequest.newBuilder().setGameId(game).setPlayerId(alice).build());

        leave(game, bob);

        assertEquals("COMPLETED", state(game).getRoundStatus());
    }

    // ---- harness ----

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

    private void houseRolls(String game, String house) {
        games.advanceToBankThrow(gameId(game));
        games.houseRoll(Games.RollRequest.newBuilder().setGameId(game).setPlayerId(house).build());
        games.advanceToPlayersThrow(gameId(game));
    }

    private String currentPlayer(String game) {
        return games.getCurrentTurn(gameId(game)).getCurrentPlayerId();
    }

    private void join(String game, String player) {
        games.joinGame(Games.JoinGameRequest.newBuilder().setGameId(game).setPlayerId(player).build());
    }

    /** House rolls, then every Player who bet rolls in turn until the round completes. */
    private void rollRound(String game, String house) {
        games.advanceToBankThrow(gameId(game));
        games.houseRoll(Games.RollRequest.newBuilder().setGameId(game).setPlayerId(house).build());
        games.advanceToPlayersThrow(gameId(game));
        while (!"COMPLETED".equals(state(game).getRoundStatus())) {
            String next = games.getCurrentTurn(gameId(game)).getCurrentPlayerId();
            games.playerRoll(Games.RollRequest.newBuilder().setGameId(game).setPlayerId(next).build());
        }
    }

    /** Creates a game with {@code house} as the House and drives it to the betting stage. */
    private String gameAtBetting(String house, String... players) {
        String game = createGame(house, players);
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

    private void markReady(String game, String player) {
        games.markPlayerReady(Games.MarkPlayerReadyRequest.newBuilder()
            .setGameId(game).setPlayerId(player).build());
    }

    private String player(double startingBalance) {
        return users.addTestPlayer(Users.UserRequest.newBuilder()
            .setBalance((float) startingBalance).build()).getId();
    }

    private String createGame(String creator, String... players) {
        String game = games.createGame(Games.CreateGameRequest.newBuilder()
            .setCreatorId(creator).build()).getId();
        for (String p : players) {
            games.joinGame(Games.JoinGameRequest.newBuilder().setGameId(game).setPlayerId(p).build());
        }
        return game;
    }

    private void leave(String game, String player) {
        games.leaveGame(Games.LeaveGameRequest.newBuilder()
            .setGameId(game).setPlayerId(player).build());
    }

    private Games.GameStateResponse state(String game) {
        return games.getGameState(gameId(game));
    }

    private static Games.GameIdRequest gameId(String game) {
        return Games.GameIdRequest.newBuilder().setGameId(game).build();
    }
}
