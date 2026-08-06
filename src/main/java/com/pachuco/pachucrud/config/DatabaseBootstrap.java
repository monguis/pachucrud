package com.pachuco.pachucrud.config;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

    private final DataSource dataSource;

    public DatabaseBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String url = dataSource.getConnection().getMetaData().getURL();
            if (url.startsWith("jdbc:postgresql:")) {
                try (var statement = dataSource.getConnection().createStatement()) {
                    statement.executeUpdate(
                        "CREATE UNIQUE INDEX IF NOT EXISTS uq_events_actor_round "
                            + "ON events (game_id, actor_id, round_number)");
                }
            }
        } catch (Exception e) {
            log.warn("Could not apply idempotency index bootstrap: {}", e.getMessage());
        }
    }
}
