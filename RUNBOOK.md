# Operations Runbook - Pachuco

Only a local Docker setup exists (`pachuco-ci-cd/dev/docker-compose.yaml`). There are no health endpoints, admin endpoints, replicas or reconciliation jobs. Why things are shaped this way: [docs/database.md](docs/database.md) and its ADRs.

**The one rule:** Postgres `events` is the truth; Redis can always be thrown away ([ADR 0003](docs/adr/0003-redis-is-a-rebuildable-cache.md)). If only one can come back, it must be Postgres.

## Start, stop, connect

```bash
cd pachuco-ci-cd/dev
docker compose up -d                 # postgres_db, redis_cache, pachucrud, pachugov2, pachuco
docker compose logs -f pachucrud     # Flyway migrations run here at startup

docker exec -it postgres_db psql -U user -d my_database
docker exec -it redis_cache redis-cli -a test
```

## Redis is wrong or corrupted

Delete the cached keys; pachucrud recomputes them from events.

```bash
docker exec redis_cache sh -c "redis-cli -a test --scan --pattern 'user:*:balance' | xargs -r redis-cli -a test del"
docker exec redis_cache sh -c "redis-cli -a test --scan --pattern 'game:*' | xargs -r redis-cli -a test del"
```

- Balances are recomputed on the next read (`BalanceCache.get`).
- Game state is rebuilt only by `joinGame`, `startRound` and one other `GameServiceImpl` path; a game nobody touches stays gone, which is the same as its 5-minute TTL expiring.
- Never `FLUSHALL`: it also drops the gateway's sessions and `gamelock_*` keys.

## Redis is down

Requests that need a balance or game state fail. That is intended: reject a bet rather than accept one you can't validate. Restart it with `docker compose restart redis`; nothing needs replaying.

## Postgres is down

Everything that writes fails, and nothing should be written to Redis alone. `docker compose restart postgres`, then restart pachucrud if its pool doesn't recover. Data lives in the `postgres_data` volume.

## Inspect a game or a balance

```sql
-- A game's event stream
SELECT sequence_number, event_type, actor_id, round_number, data, created_at
FROM events WHERE game_id = '<game-id>' ORDER BY sequence_number;

-- Everything a user did (deposits have game_id NULL)
SELECT event_type, game_id, data, created_at
FROM events WHERE actor_id = '<user-id>' ORDER BY created_at;

-- Applied migrations
SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;
```

There is no SQL equivalent of a balance: the rules (including the House's side, found via `data->>'houseId'`) are in `EventService.computeUserBalance`. Compare its result with `redis-cli -a test get user:<id>:balance`; if they differ, delete the Redis key.

## Reset the dev database

```bash
docker compose down -v   # DESTROYS postgres_data and redis_data
docker compose up -d     # Flyway recreates the schema from V1
```
