-- Tracks where a user's avatar came from, so a login-time sync never overwrites a manually chosen avatar.
CREATE TABLE "${flyway:defaultSchema}".user_avatar_source
(
    user_id UUID NOT NULL PRIMARY KEY REFERENCES "${flyway:defaultSchema}"."user" (id),
    source TEXT NOT NULL CHECK (source IN ('google', 'manual')),
    source_url TEXT NULL,
    avatar_url TEXT NOT NULL,
    synced_at TIMESTAMPTZ NOT NULL
);
