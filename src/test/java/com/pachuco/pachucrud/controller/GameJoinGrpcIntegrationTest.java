package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.service.RedisService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Players join a game over real gRPC against real Postgres and real Redis: a player who
 * joins while a round is under way is a Joined Player until the next Lobby.
 */
@SpringBootTest(properties = {
    "spring.grpc.server.port=0",
    "spring.jpa.show-sql=false",
    "test-players.enabled=true"
})
@Testcontainers
class GameJoinGrpcIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired RedisService redisService;

    @Value("${game.max.players}") int maxPlayers;

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
    void aPlayerWhoJoinsDuringARoundSitsItOut() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        String carol = player(100);

        join(game, carol);

        Games.GameStateResponse round = state(game);
        assertEquals(List.of(house, alice, bob), round.getTurnOrderList());
        assertTrue(round.getWaitingPlayersList().contains(carol));
    }

    @Test
    void aPlayerWhoJoinsOnceTheHouseIsChosenSitsTheRoundOut() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = createGame(house, alice, bob);
        games.advanceToEnoughPlayers(gameId(game));
        readyUp(game, house, alice, bob);
        games.advanceToGameStart(Games.AdvanceToGameStartRequest.newBuilder()
            .setGameId(game).addAllPlayerOrder(List.of(house, alice, bob)).build());
        String carol = player(100);

        join(game, carol);

        Games.GameStateResponse round = state(game);
        assertEquals("GAME_START", round.getRoundStatus());
        assertEquals(List.of(house, alice, bob), round.getTurnOrderList());
        assertTrue(round.getWaitingPlayersList().contains(carol));
    }

    @Test
    void aPlayerWhoJoinedDuringTheRoundIsAJoinedPlayerInTheNextLobby() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        String carol = player(100);
        join(game, carol);
        bet(game, alice, 10);
        bet(game, bob, 10);
        rollRound(game);

        games.advanceToNextRound(gameId(game));
        markReady(game, carol);

        Games.GameStateResponse lobby = state(game);
        assertEquals("ENOUGH_PLAYERS", lobby.getRoundStatus());
        assertTrue(lobby.getReadyPlayersList().contains(carol));
    }

    @Test
    void aPlayerWhoJoinedDuringAnAbandonedRoundIsAJoinedPlayerInTheNextLobby() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        String carol = player(100);
        join(game, carol);

        leave(game, house);

        Games.GameStateResponse lobby = state(game);
        assertEquals("ENOUGH_PLAYERS", lobby.getRoundStatus());
        assertTrue(lobby.getWaitingPlayersList().contains(carol));
    }

    @Test
    void rebuildingTheGameFromEventsKeepsAMidRoundJoinerOutOfTheRound() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        String carol = player(100);
        join(game, carol);
        bet(game, alice, 10);
        bet(game, bob, 10);
        rollRound(game);
        games.advanceToNextRound(gameId(game));
        Games.GameStateResponse live = state(game);

        redisService.deleteGameState(UUID.fromString(game));

        Games.GameStateResponse rebuilt = state(game);
        assertEquals(live.getTurnOrderList(), rebuilt.getTurnOrderList());
        assertEquals(live.getWaitingPlayersList(), rebuilt.getWaitingPlayersList());
        assertTrue(rebuilt.getWaitingPlayersList().contains(carol));
    }

    @Test
    void aFullTableCountsPlayersWhoJoinedDuringTheRound() {
        String house = player(1000);
        List<String> rotation = new ArrayList<>();
        for (int i = 1; i < maxPlayers - 1; i++) {
            rotation.add(player(100));
        }
        String game = gameAtBetting(house, rotation.toArray(String[]::new));
        join(game, player(100));

        StatusRuntimeException full = assertThrows(StatusRuntimeException.class,
            () -> join(game, player(100)));
        assertTrue(full.getStatus().getDescription().contains("full"));
    }

    @Test
    void aPlayerWhoJoinedDuringTheRoundPlaysTheNextOneFromTheEndOfTheRotation() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        String carol = player(100);
        join(game, carol);
        List<String> lobbyRotation = finishRound(game);

        readyUp(game, house, alice, bob, carol);
        startNextRound(game);

        Games.GameStateResponse round = state(game);
        List<String> expected = new ArrayList<>(lobbyRotation);
        expected.add(carol);
        assertEquals(expected, round.getTurnOrderList());
        assertEquals(lobbyRotation.get(0), round.getHousePlayerId());
    }

    @Test
    void aPlayerWhoIsNotReadySitsOutAndGoesBackInAtTheEndOfTheRotation() {
        // the dice decide who holds the House in the second round, so anyone must be able to cover it
        String game = gameAtBetting(player(1000), player(1000), player(1000), player(1000));
        List<String> lobby = finishRound(game);
        String sitsOut = lobby.get(1);

        readyUp(game, lobby.get(0), lobby.get(2), lobby.get(3));
        startNextRound(game);
        assertEquals(List.of(lobby.get(0), lobby.get(2), lobby.get(3)), state(game).getTurnOrderList());

        playBettingStage(game);
        List<String> nextLobby = finishRound(game);
        readyUp(game, nextLobby.toArray(String[]::new));
        readyUp(game, sitsOut);
        startNextRound(game);

        List<String> expected = new ArrayList<>(nextLobby);
        expected.add(sitsOut);
        assertEquals(expected, state(game).getTurnOrderList());
    }

    @Test
    void aHouseThatIsNotReadyPassesTheHouseToTheNextReadyPlayer() {
        String game = gameAtBetting(player(1000), player(100), player(100));
        List<String> lobby = finishRound(game);

        readyUp(game, lobby.get(1), lobby.get(2));
        startNextRound(game);

        Games.GameStateResponse round = state(game);
        assertEquals(lobby.get(1), round.getHousePlayerId());
        assertEquals(List.of(lobby.get(1), lobby.get(2)), round.getTurnOrderList());
    }

    @Test
    void aPlayerWhoJoinedDuringTheRoundCannotBetInIt() {
        String house = player(1000);
        String alice = player(100);
        String bob = player(100);
        String game = gameAtBetting(house, alice, bob);
        String carol = player(100);
        join(game, carol);

        assertThrows(StatusRuntimeException.class, () -> bet(game, carol, 10));
        assertTrue(state(game).getBetsList().isEmpty());
    }

    // ---- harness ----

    /** The House sets the bet limit and betting opens. */
    private void playBettingStage(String game) {
        String house = state(game).getHousePlayerId();
        games.startRound(Games.StartRoundRequest.newBuilder()
            .setGameId(game).setHousePlayerId(house).setBetLimit(50).build());
        games.advanceToPlayersBetSetting(gameId(game));
    }

    /**
     * Everyone but the House bets, the round ends and the next Lobby opens. The dice decide
     * whether the House passes on, so the Lobby's Rotation is returned for the caller to use.
     */
    private List<String> finishRound(String game) {
        Games.GameStateResponse round = state(game);
        for (String p : round.getTurnOrderList()) {
            if (!p.equals(round.getHousePlayerId())) {
                bet(game, p, 10);
            }
        }
        rollRound(game);
        games.advanceToNextRound(gameId(game));
        return state(game).getTurnOrderList();
    }

    /** After the first round pachucrud builds the Rotation itself, so no order is sent. */
    private void startNextRound(String game) {
        games.advanceToGameStart(Games.AdvanceToGameStartRequest.newBuilder().setGameId(game).build());
    }

    private void readyUp(String game, String... players) {
        for (String p : players) {
            markReady(game, p);
        }
    }

    private void leave(String game, String player) {
        games.leaveGame(Games.LeaveGameRequest.newBuilder()
            .setGameId(game).setPlayerId(player).build());
    }

    private void bet(String game, String player, double amount) {
        games.placeBet(Games.PlaceBetRequest.newBuilder()
            .setGameId(game).setPlayerId(player).setAmount(amount).build());
    }

    /** The House and every Player who bet roll in one go, then the round completes. */
    private void rollRound(String game) {
        games.advanceToDiceRolling(gameId(game));
        games.advanceToRoundCompleted(gameId(game));
    }

    private void join(String game, String player) {
        games.joinGame(Games.JoinGameRequest.newBuilder().setGameId(game).setPlayerId(player).build());
    }

    /** Creates a game with {@code house} as the House and drives it to the betting stage. */
    private String gameAtBetting(String house, String... players) {
        String game = createGame(house, players);
        games.advanceToEnoughPlayers(gameId(game));
        markReady(game, house);
        for (String p : players) {
            markReady(game, p);
        }
        games.advanceToGameStart(Games.AdvanceToGameStartRequest.newBuilder()
            .setGameId(game).addPlayerOrder(house).addAllPlayerOrder(List.of(players)).build());
        games.startRound(Games.StartRoundRequest.newBuilder()
            .setGameId(game).setHousePlayerId(house).setBetLimit(50).build());
        games.advanceToPlayersBetSetting(gameId(game));
        return game;
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
            join(game, p);
        }
        return game;
    }

    private Games.GameStateResponse state(String game) {
        return games.getGameState(gameId(game));
    }

    private static Games.GameIdRequest gameId(String game) {
        return Games.GameIdRequest.newBuilder().setGameId(game).build();
    }
}
