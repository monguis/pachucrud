-- Formerly applied at startup by DatabaseBootstrap.
CREATE UNIQUE INDEX IF NOT EXISTS uq_events_actor_round
    ON events (game_id, actor_id, round_number);
