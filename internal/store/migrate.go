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
	execSQL(`
	-- Times are Unix milliseconds. A deleted document keeps its row until
	-- the trash is purged, so the delete can be undone.
	CREATE TABLE documents (
		id         INTEGER PRIMARY KEY,
		user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
		title      TEXT    NOT NULL,
		color      TEXT    NOT NULL,
		content    TEXT    NOT NULL DEFAULT '',
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL,
		deleted_at INTEGER
	);
	CREATE INDEX documents_user ON documents (user_id, updated_at DESC) WHERE deleted_at IS NULL;
	CREATE INDEX documents_deleted ON documents (deleted_at) WHERE deleted_at IS NOT NULL;
	`),
	execSQL(`
	-- Pasted images. The bytes live in files under the data dir; a row
	-- records who owns the image and how to serve it.
	CREATE TABLE images (
		id         TEXT    PRIMARY KEY,
		user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
		mime       TEXT    NOT NULL,
		size       INTEGER NOT NULL,
		created_at INTEGER NOT NULL
	);
	CREATE INDEX images_created ON images (created_at);
	`),
	execSQL(`
	-- Dictated text not yet tidied, as a JSON array of [from, to] offsets
	-- into content. Clients keep it in step with content.
	ALTER TABLE documents ADD COLUMN tidy_ranges TEXT NOT NULL DEFAULT '[]';
	`),
	execSQL(`
	-- Full-text index over titles and content. The trigram tokenizer finds
	-- any substring of three or more characters, which suits Chinese
	-- without word segmentation. Triggers keep it in step with documents;
	-- deleted documents stay indexed and are filtered out by the query.
	CREATE VIRTUAL TABLE documents_fts USING fts5(
		title, content,
		content = 'documents', content_rowid = 'id',
		tokenize = 'trigram'
	);
	CREATE TRIGGER documents_fts_insert AFTER INSERT ON documents BEGIN
		INSERT INTO documents_fts (rowid, title, content) VALUES (new.id, new.title, new.content);
	END;
	CREATE TRIGGER documents_fts_delete AFTER DELETE ON documents BEGIN
		INSERT INTO documents_fts (documents_fts, rowid, title, content) VALUES ('delete', old.id, old.title, old.content);
	END;
	CREATE TRIGGER documents_fts_update AFTER UPDATE OF title, content ON documents BEGIN
		INSERT INTO documents_fts (documents_fts, rowid, title, content) VALUES ('delete', old.id, old.title, old.content);
		INSERT INTO documents_fts (rowid, title, content) VALUES (new.id, new.title, new.content);
	END;
	INSERT INTO documents_fts (documents_fts) VALUES ('rebuild');
	`),
	execSQL(`
	-- Native clients signed in through /auth/device/login. Like sessions,
	-- a device token never expires; the row lives until it is revoked.
	-- Times are Unix milliseconds.
	CREATE TABLE devices (
		id           INTEGER PRIMARY KEY,
		user_id      INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
		name         TEXT    NOT NULL,
		platform     TEXT    NOT NULL,
		token_hash   TEXT    NOT NULL UNIQUE,
		created_at   INTEGER NOT NULL,
		last_used_at INTEGER NOT NULL
	);
	CREATE INDEX devices_user ON devices (user_id);
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
