-- ddl-auto=update never widened this check when TEST_PLAYER was added to UserRole.
ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS user_roles_role_check;
ALTER TABLE user_roles ADD CONSTRAINT user_roles_role_check
    CHECK (role IN ('PLAYER', 'ADMIN', 'TEST_PLAYER'));
