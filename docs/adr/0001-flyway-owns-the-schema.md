# Flyway owns the schema, not Hibernate

Hibernate's `ddl-auto=update` silently left `user_roles_role_check` at `('PLAYER','ADMIN')` after `TEST_PLAYER` was added to `UserRole`, so creating a Test Player failed at runtime while H2 `create-drop` tests passed. Schema changes are now versioned Flyway migrations and Hibernate runs with `ddl-auto=validate`; existing databases are adopted with `baseline-on-migrate` at V1 rather than wiped, because balances are derived from the `events` table.

## Consequences

- Adding a value to any `@Enumerated(EnumType.STRING)` enum requires a migration that rewrites its check constraint.
- Startup-time schema code (the former `DatabaseBootstrap` index) is not allowed; it belongs in a migration.
- Unit tests stay on H2 with Flyway disabled; one Testcontainers Postgres test runs every migration and then Hibernate `validate`, which is the guard against entity/schema drift.
