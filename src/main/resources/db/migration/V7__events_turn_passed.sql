-- Every hand is rolled when betting closes; a player's Roll only passes the turn on,
-- recorded as a TURN_PASSED event carrying the seat whose turn it now is.
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_event_type_check;
ALTER TABLE events ADD CONSTRAINT events_event_type_check
    CHECK (event_type IN ('GAME_CREATED', 'PLAYER_JOINED', 'PLAYER_READY', 'ROUND_STARTED',
        'BET_LIMIT_SET', 'BET_PLACED', 'HOUSE_ROLLED', 'PLAYER_ROLLED', 'ROUND_COMPLETED',
        'GAME_COMPLETED', 'GAME_CANCELLED', 'GAME_ADVANCED', 'PLAYER_LEFT', 'BET_RELEASED',
        'ROUND_ABANDONED', 'TURN_PASSED', 'DEPOSIT', 'WITHDRAWAL'));
