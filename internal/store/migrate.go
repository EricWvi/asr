package store

import (
	"database/sql"
	"fmt"
)

// A migration runs inside its own transaction.
type migration func(tx *sql.Tx) error

func execSQL(query string) migration {
	return func(tx *sql.Tx) error {
		_, err := tx.Exec(query)
		return err
	}
}

// migrations are applied in order; PRAGMA user_version records progress.
// Never edit a released migration, append a new one instead.
var migrations = []migration{
	execSQL(`
	CREATE TABLE users (
		id         INTEGER PRIMARY KEY,
		issuer     TEXT NOT NULL,
		subject    TEXT NOT NULL,
		name       TEXT NOT NULL DEFAULT '',
		email      TEXT NOT NULL DEFAULT '',
		created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
		UNIQUE (issuer, subject)
	);

	-- Sessions never expire; a row lives until the user signs out.
	CREATE TABLE sessions (
		token_hash   TEXT    PRIMARY KEY,
		user_id      INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
		created_at   INTEGER NOT NULL,
		last_seen_at INTEGER NOT NULL
	);
	CREATE INDEX sessions_user ON sessions (user_id);
	`),
}

func migrate(db *sql.DB) error {
	var version int
	if err := db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	for i := version; i < len(migrations); i++ {
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		if err := migrations[i](tx); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, i+1)); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
	}
	return nil
}
