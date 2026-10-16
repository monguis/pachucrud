package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs the real Flyway migrations against Postgres and lets Hibernate validate the
 * entities against the result. Guards against entity/schema drift (docs/adr/0001),
 * which H2 + create-drop in the other tests cannot see.
 */
@SpringBootTest(properties = {
    "spring.grpc.server.port=0",
    "spring.jpa.show-sql=false"
})
@Testcontainers
class SchemaMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void freshDatabaseMigratesAndValidates() {
        Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertEquals(3, applied);
    }

    @Test
    void persistsEveryUserRole() {
        UserEntity user = new UserEntity();
        user.setAuthId("auth|" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setRoles(List.of(UserRole.values()));

        assertNotNull(userRepository.saveAndFlush(user).getId());
    }

    @Test
    void legacyDatabaseIsBaselinedAndRepaired() throws Exception {
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/legacy");
        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE legacy");
        }
        // The shape ddl-auto=update left behind: TEST_PLAYER missing from the role check.
        try (Connection legacy = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword());
             Statement st = legacy.createStatement()) {
            st.execute("CREATE TABLE users (id uuid PRIMARY KEY)");
            st.execute("CREATE TABLE events (id uuid PRIMARY KEY, game_id uuid, actor_id uuid,"
                + " round_number integer)");
            st.execute("CREATE TABLE user_roles (user_id uuid NOT NULL REFERENCES users(id),"
                + " role varchar(255), CONSTRAINT user_roles_role_check"
                + " CHECK (role IN ('PLAYER', 'ADMIN')))");
        }

        Flyway.configure()
            .dataSource(url, postgres.getUsername(), postgres.getPassword())
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load()
            .migrate();

        try (Connection legacy = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword());
             Statement st = legacy.createStatement()) {
            UUID id = UUID.randomUUID();
            st.execute("INSERT INTO users (id) VALUES ('" + id + "')");
            st.execute("INSERT INTO user_roles (user_id, role) VALUES ('" + id + "', 'TEST_PLAYER')");
        }
    }
}
