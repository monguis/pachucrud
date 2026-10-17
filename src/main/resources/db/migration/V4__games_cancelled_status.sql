-- A game whose first Lobby fails becomes CANCELLED, recorded by a GAME_CANCELLED event.
ALTER TABLE games DROP CONSTRAINT IF EXISTS games_status_check;
ALTER TABLE games ADD CONSTRAINT games_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'));

ALTER TABLE events DROP CONSTRAINT IF EXISTS events_event_type_check;
ALTER TABLE events ADD CONSTRAINT events_event_type_check
    CHECK (event_type IN ('GAME_CREATED', 'PLAYER_JOINED', 'PLAYER_READY', 'ROUND_STARTED',
        'BET_LIMIT_SET', 'BET_PLACED', 'HOUSE_ROLLED', 'PLAYER_ROLLED', 'ROUND_COMPLETED',
        'GAME_COMPLETED', 'GAME_CANCELLED', 'GAME_ADVANCED', 'DEPOSIT', 'WITHDRAWAL'));
