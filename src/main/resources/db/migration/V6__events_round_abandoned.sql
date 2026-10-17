-- The House leaving before it rolls abandons the round: a ROUND_ABANDONED event,
-- with a BET_VOIDED event refunding each Bet not yet settled.
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_event_type_check;
ALTER TABLE events ADD CONSTRAINT events_event_type_check
    CHECK (event_type IN ('GAME_CREATED', 'PLAYER_JOINED', 'PLAYER_READY', 'ROUND_STARTED',
        'BET_LIMIT_SET', 'BET_PLACED', 'HOUSE_ROLLED', 'PLAYER_ROLLED', 'ROUND_COMPLETED',
        'GAME_COMPLETED', 'GAME_CANCELLED', 'GAME_ADVANCED', 'PLAYER_LEFT', 'BET_VOIDED',
        'ROUND_ABANDONED', 'DEPOSIT', 'WITHDRAWAL'));
