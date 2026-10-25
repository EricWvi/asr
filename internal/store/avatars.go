package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
)

// MaxAvatarSize is the largest avatar a user can upload, in bytes. The web
// app uploads a 256×256 crop, far below this.
const MaxAvatarSize = 1 << 20

// AvatarURLPrefix starts the URL of every avatar. The ID changes with each
// upload, so the URL can be cached forever.
const AvatarURLPrefix = "/api/avatars/"

// AvatarURL is where the user's avatar is served, or "" without one.
func (u User) AvatarURL() string {
	if u.Avatar == "" {
		return ""
	}
	return AvatarURLPrefix + u.Avatar
}

// User returns the user with the given ID.
func (s *Store) User(ctx context.Context, id int64) (User, error) {
	u := User{ID: id}
	err := s.db.QueryRowContext(ctx, `SELECT name, email, avatar FROM users WHERE id = ?`, id).
		Scan(&u.Name, &u.Email, &u.Avatar)
	if errors.Is(err, sql.ErrNoRows) {
		return u, ErrNotFound
	}
	return u, err
}

// SetAvatar replaces the user's avatar with data and returns the new avatar
// ID. The format is taken from the bytes, not from what the client claims.
func (s *Store) SetAvatar(ctx context.Context, userID int64, data []byte) (string, error) {
	if len(data) == 0 {
		return "", invalid("图片是空的")
	}
	if len(data) > MaxAvatarSize {
		return "", invalid("头像不能超过 1 MB")
	}
	mime := http.DetectContentType(data)
	if !imageTypes[mime] {
		return "", invalid("只支持 PNG、JPEG、GIF 和 WebP 图片")
	}
	id := newImageID()
	path := s.avatarPath(userID, id)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return "", fmt.Errorf("create avatar dir: %w", err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return "", fmt.Errorf("write avatar: %w", err)
	}
	old, err := s.swapAvatar(ctx, userID, id)
	if err != nil {
		os.Remove(path)
		return "", err
	}
	s.removeAvatar(userID, old)
	return id, nil
}

// DeleteAvatar removes the user's avatar. Removing a missing one is not an
// error.
func (s *Store) DeleteAvatar(ctx context.Context, userID int64) error {
	old, err := s.swapAvatar(ctx, userID, "")
	if err != nil {
		return err
	}
	s.removeAvatar(userID, old)
	return nil
}

// AvatarPath returns the file of the user's avatar if id is still its
// current ID.
func (s *Store) AvatarPath(ctx context.Context, userID int64, id string) (string, error) {
	var current string
	err := s.db.QueryRowContext(ctx, `SELECT avatar FROM users WHERE id = ?`, userID).Scan(&current)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (current == "" || current != id) {
		return "", ErrNotFound
	}
	return s.avatarPath(userID, id), err
}

// ReadAvatar returns the bytes of the user's avatar, or nil without one.
func (s *Store) ReadAvatar(u User) ([]byte, error) {
	if u.Avatar == "" {
		return nil, nil
	}
	return os.ReadFile(s.avatarPath(u.ID, u.Avatar))
}

// swapAvatar records id as the user's avatar and returns the previous one.
func (s *Store) swapAvatar(ctx context.Context, userID int64, id string) (string, error) {
	var old string
	err := s.inTx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx, `SELECT avatar FROM users WHERE id = ?`, userID).Scan(&old)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `UPDATE users SET avatar = ? WHERE id = ?`, id, userID)
		return err
	})
	return old, err
}

func (s *Store) removeAvatar(userID int64, id string) {
	if id == "" {
		return
	}
	// A leftover file is harmless: nothing refers to it any more.
	os.Remove(s.avatarPath(userID, id))
}

func (s *Store) avatarPath(userID int64, id string) string {
	return filepath.Join(s.dir, "avatars", strconv.FormatInt(userID, 10), id)
}
