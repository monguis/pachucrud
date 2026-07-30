# Operations Runbook — Pachuco Dice Game

## Architecture Overview

```
Client → Middleware → Redis (hot path) → PostgreSQL (authoritative)
                       ↑                        ↓
                   reads from              append-only
                   Redis first             event log
```

- **Redis** holds live state: `user:{id}:balance`, `game:{id}:state`, `game:{id}:events:last_seq`
- **PostgreSQL** is the append-only source of truth (events table)
- Writes always go to PG first, then Redis is updated

---

## Redis Down

### Detection

- Middleware gets connection errors or timeouts on Redis
- Health check endpoint (`/health`) reports Redis as unhealthy
- Metrics show Redis latency spikes then drops to zero

### Immediate Response (automated)

1. **Reads fall through to PG** — middleware catches Redis errors and queries PG directly. Slower but correct.
2. **Writes are queued or fail-fast** depending on criticality:
   - Bet placement: **fail-fast** (return error to player). Never accept a bet you can't validate against live balance.
   - Non-critical writes (profile updates): **queue** in PG directly or a dead-letter queue.
3. **Alert fires** to on-call.

### Recovery Procedure

#### 1. Verify Redis process
```bash
redis-cli ping
# expect: PONG
systemctl status redis
# or: docker ps | grep redis
```

#### 2. If Redis is running but corrupted
```bash
# Flush all game state (NOT flushall — that clears everything including config)
redis-cli --scan --pattern 'user:*:balance' | xargs redis-cli del
redis-cli --scan --pattern 'game:*' | xargs redis-cli del
```

#### 3. Rebuild live state from PG events

Run the rebuild script:
```bash
# Rebuild all active games
curl -X POST /admin/redis/rebuild

# Or rebuild specific game
curl -X POST /admin/redis/rebuild?game_id=<uuid>
```

What the rebuild does internally:
```sql
-- For each active game, fetch all events in order
SELECT * FROM events WHERE game_id = ? ORDER BY sequence_number;

-- Replay each event to reconstruct:
--   game:{id}:state (current round, house, bets, rolls, turn order)
--   user:{id}:balance (sum deposits - holds + wins - losses)

-- Write reconstructed state to Redis
```

#### 4. Verify recovery
```bash
# Check a known game state
redis-cli get game:<id>:state
# Check a user balance
redis-cli get user:<id>:balance
```

#### 5. Resume normal operations
- Middleware detects Redis is responsive again
- Falls back to normal read path
- Acknowledge alert

### Expected Recovery Time

| Games | Events | Rebuild Time |
|---|---|---|
| < 10 | < 10K | < 1s |
| < 100 | < 100K | ~2-5s |
| < 1000 | < 1M | ~10-30s |
| 1000+ | 10M+ | minutes (consider partial rebuild — only active games) |

---

## PostgreSQL Down

### Severity: **Critical** — PG is the source of truth.

### Detection

- PG connection pool exhaustion
- Timeouts on write operations
- Health check reports PG as unhealthy

### Immediate Response (automated)

1. **All writes stop** — return 503 to all clients. PG is the authoritative store; accepting writes without it means potential data loss.
2. **Redis is read-only** — live state is served for reads, but no new bets, rolls, or games.
3. **Alert fires** to on-call (page-level).

### Recovery

#### 1. Check PG status
```bash
# Can you connect?
psql -h <host> -U pachuco -d pachucodb -c "SELECT 1;"

# Check replication status (if replica)
psql -h <host> -U pachuco -d pachucodb -c "SELECT * FROM pg_stat_replication;"

# Check for stuck queries
psql -h <host> -U pachuco -d pachucodb -c "SELECT pid, now() - pg_stat_activity.query_start AS duration, query, state FROM pg_stat_activity WHERE state != 'idle' ORDER BY duration DESC;"
```

#### 2. If a simple restart fixes it
```bash
systemctl restart postgresql
# or: docker restart pachuco-pg
```

#### 3. If data corruption or hardware failure
- Restore from the latest WAL archive
- If using a replica, promote it:
  ```sql
  SELECT pg_promote();
  ```
- Update connection strings in middleware config

#### 4. Verify data integrity
```sql
-- Check event log is intact
SELECT count(*), max(created_at) FROM events;

-- Spot-check a game's event sequence
SELECT sequence_number, event_type, created_at
FROM events WHERE game_id = '<recent-game-id>'
ORDER BY sequence_number;
```

#### 5. Resume writes
- Update middleware connection pool to point at the restored DB
- Verify reads and writes work
- Acknowledge alert

### Expected Recovery Time

| Scenario | Time |
|---|---|
| PG process restart | 10-30s |
| Replica promotion | 1-5 min |
| WAL restore from backup | 10-60 min |

---

## Both Redis AND PostgreSQL Down

### Severity: **Catastrophic** — full system outage.

### Response

1. **Take the application offline** — return maintenance page (503).
2. **Bring up PG first** — it's the authoritative store. Follow PG recovery steps above.
3. **Then rebuild Redis** from PG (see Redis recovery steps).
4. **Verify** both systems are consistent before reopening traffic.

This is why the architecture writes to PG first: if only one can come back, it must be PG.

---

## Split Brain / Inconsistency Between Redis and PG

### Detection

- A player reports an incorrect balance
- A game shows wrong state in UI vs what PG events say
- Periodic reconciliation job alerts on mismatches

### Investigation

```sql
-- Get the actual balance from PG events
SELECT actor_id,
       sum(CASE WHEN event_type = 'bet_placed' THEN -data->>'amount'
                WHEN event_type = 'bet_won'    THEN data->>'amount'
                WHEN event_type = 'bet_lost'   THEN 0
                ELSE 0 END) AS computed_balance
FROM events
WHERE actor_id = '<user-id>'
GROUP BY actor_id;
```

Compare with:
```bash
redis-cli get user:<id>:balance
```

### Resolution

1. **Trust PG** — PG events are the source of truth.
2. **Fix Redis** by running the rebuild for the affected user/game:
   ```bash
   curl -X POST /admin/redis/rebuild?user_id=<uuid>
   ```
3. **Investigate root cause** — was it a code bug, a race condition, or a missed Redis write after PG commit?

---

## Bet Settlement Failure (PG write succeeds, Redis update fails)

### The Problem

```
INSERT INTO events (bet_settled) → PG OK ✓
UPDATE user:{id}:balance in Redis → Redis DOWN ✗
```

Now PG says the bet is settled but Redis shows the old balance.

### Automatic Recovery

The middleware should have a **reconciliation worker** that runs every N seconds:

```sql
-- Find events that don't have a corresponding Redis state
SELECT e.id, e.game_id, e.sequence_number, e.event_type, e.data
FROM events e
WHERE e.created_at > now() - interval '5 minutes'
  AND NOT EXISTS (
    -- Check if the Redis seq tracker is caught up
    -- This is an application-level check
  )
ORDER BY e.created_at;
```

The worker replays these events into Redis until it's caught up.

### Manual Fix

```bash
# Rebuild the game to fix everything
curl -X POST /admin/redis/rebuild?game_id=<uuid>

# Or just fix one user's balance
curl -X POST /admin/redis/rebuild?user_id=<uuid>
```

---

## Key Principles

| Rule | Why |
|---|---|
| **Write to PG first, Redis second** | PG is the truth. If Redis update fails, PG is still correct. |
| **Never accept writes with Redis only** | Redis is ephemeral. A write that only touches Redis is lost on restart. |
| **Fail fast on Redis downtime for bets** | It's safer to reject a bet than to accept one you can't validate. |
| **Dry-run rebuilds before applying** | The rebuild script should support `?dry_run=true` to log what it would write without touching Redis. |
| **Monitor the divergence** | Track `last_known_seq` per game in PG. If Redis falls behind by more than N events, alert. |

---

## Monitoring Checklist

| What | How | Threshold | Severity |
|---|---|---|---|
| Redis reachable | Health check ping | Any failure = alert | High |
| PG reachable | Connection pool health | Pool exhaustion = alert | Critical |
| Redis vs PG lag | Compare last_seq in PG vs Redis | > 10 events divergence | Warning |
| Write queue depth | Events waiting to sync to Redis | > 100 | Warning |
| Bet failure rate | % of bet placements that error | > 1% | High |
| Rebuild duration | Time to rebuild Redis from PG | > 60s | Warning |
