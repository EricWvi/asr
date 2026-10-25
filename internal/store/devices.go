package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"errors"
	"strings"
	"time"
	"unicode/utf8"
)

// Platforms of native clients.
const (
	PlatformMac     = "mac"
	PlatformAndroid = "android"
)

// DeviceTokenPrefix starts every device token so a leaked one is easy to
// recognise.
const DeviceTokenPrefix = "asr_d_"

// MaxDeviceName caps the name a client reports for itself, in characters.
const MaxDeviceName = 64

// Device is a native client signed in as a user. Times are Unix
// milliseconds.
type Device struct {
	ID         int64  `json:"id"`
	UserID     int64  `json:"-"`
	Name       string `json:"name"`
	Platform   string `json:"platform"`
	CreatedAt  int64  `json:"created_at"`
	LastUsedAt int64  `json:"last_used_at"`
}

// ValidPlatform reports whether p names a supported native client.
func ValidPlatform(p string) bool { return p == PlatformMac || p == PlatformAndroid }

// CleanDeviceName trims a reported device name and checks its length.
func CleanDeviceName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", invalid("设备名不能为空")
	}
	if utf8.RuneCountInString(name) > MaxDeviceName {
		return "", invalid("设备名太长")
	}
	return name, nil
}

// CreateDevice records a newly signed-in device and returns it with the
// token the client presents from now on. Only the token's hash is stored.
func (s *Store) CreateDevice(ctx context.Context, userID int64, name, platform string) (Device, string, error) {
	name, err := CleanDeviceName(name)
	if err != nil {
		return Device{}, "", err
	}
	if !ValidPlatform(platform) {
		return Device{}, "", invalid("不支持的平台")
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return Device{}, "", err
	}
	token := DeviceTokenPrefix + base64.RawURLEncoding.EncodeToString(raw)
	now := time.Now().UnixMilli()
	d := Device{UserID: userID, Name: name, Platform: platform, CreatedAt: now, LastUsedAt: now}
	res, err := s.db.ExecContext(ctx, `
		INSERT INTO devices (user_id, name, platform, token_hash, created_at, last_used_at)
		VALUES (?, ?, ?, ?, ?, ?)`, userID, name, platform, hashToken(token), now, now)
	if err != nil {
		return Device{}, "", err
	}
	d.ID, err = res.LastInsertId()
	return d, token, err
}

// DeviceUser resolves a device token to its device and user.
func (s *Store) DeviceUser(ctx context.Context, token string) (Device, User, error) {
	var d Device
	var u User
	err := s.db.QueryRowContext(ctx, `
		SELECT d.id, d.name, d.platform, d.created_at, d.last_used_at, u.id, u.name, u.email
		FROM devices d JOIN users u ON u.id = d.user_id
		WHERE d.token_hash = ?`, hashToken(token)).
		Scan(&d.ID, &d.Name, &d.Platform, &d.CreatedAt, &d.LastUsedAt, &u.ID, &u.Name, &u.Email)
	if errors.Is(err, sql.ErrNoRows) {
		return d, u, ErrNotFound
	}
	d.UserID = u.ID
	return d, u, err
}

// TouchDevice records that the device is still in use.
func (s *Store) TouchDevice(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET last_used_at = ? WHERE id = ?`, time.Now().UnixMilli(), id)
	return err
}

// ListDevices returns the user's devices, most recently used first.
func (s *Store) ListDevices(ctx context.Context, userID int64) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, name, platform, created_at, last_used_at FROM devices
		WHERE user_id = ? ORDER BY last_used_at DESC, id DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		d := Device{UserID: userID}
		if err := rows.Scan(&d.ID, &d.Name, &d.Platform, &d.CreatedAt, &d.LastUsedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// DeleteDevice revokes a device; its token stops working at once.
func (s *Store) DeleteDevice(ctx context.Context, userID, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM devices WHERE id = ? AND user_id = ?`, id, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}
