// Package store persists users, sessions, documents and images in SQLite
// and the data dir.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite" // CGO-free SQLite driver
)

var (
	// ErrNotFound reports a missing row.
	ErrNotFound = errors.New("not found")
	// ErrInvalid reports rejected input; the wrapped message is user-facing.
	ErrInvalid = errors.New("invalid input")
	// ErrConflict reports an operation that the current state forbids.
	ErrConflict = errors.New("conflict")
)

// Store is safe for concurrent use. Every query on user content is scoped
// to one user.
type Store struct {
	db  *sql.DB
	dir string
}

// Open opens (and migrates) the database inside dataDir. Uploaded files
// are kept next to it.
func Open(dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("create data dir: %w", err)
	}
	dsn := "file:" + filepath.Join(dataDir, "asr.db") +
		"?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	// A single connection serialises writes and keeps transactions simple.
	db.SetMaxOpenConns(1)
	if err := migrate(db); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db, dir: dataDir}, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

func (s *Store) inTx(ctx context.Context, fn func(*sql.Tx) error) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}

func invalid(msg string) error { return fmt.Errorf("%w: %s", ErrInvalid, msg) }
