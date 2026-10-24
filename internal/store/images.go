package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// MaxImageSize is the largest image a user can upload, in bytes.
const MaxImageSize = 10 << 20

// ImageRetention is how long an image no document refers to is kept
// before PurgeImages removes it. A fresh upload is not yet saved in its
// document, and an image removed by an edit can come back with undo.
const ImageRetention = 30 * 24 * time.Hour

// ImageURLPrefix starts the URL of every image; documents refer to images
// by this URL, so PurgeImages looks for it in their content.
const ImageURLPrefix = "/api/images/"

// imageTypes are the formats accepted, by sniffed MIME type.
var imageTypes = map[string]bool{"image/png": true, "image/jpeg": true, "image/gif": true, "image/webp": true}

// Image is an uploaded picture.
type Image struct {
	ID   string `json:"id"`
	URL  string `json:"url"`
	MIME string `json:"-"`
	Size int64  `json:"-"`
}

// CreateImage stores data as an image of the user. The format is taken
// from the bytes, not from what the client claims.
func (s *Store) CreateImage(ctx context.Context, userID int64, data []byte) (Image, error) {
	if len(data) == 0 {
		return Image{}, invalid("图片是空的")
	}
	if len(data) > MaxImageSize {
		return Image{}, invalid("图片不能超过 10 MB")
	}
	mime := http.DetectContentType(data)
	if !imageTypes[mime] {
		return Image{}, invalid("只支持 PNG、JPEG、GIF 和 WebP 图片")
	}
	img := Image{ID: newImageID(), MIME: mime, Size: int64(len(data))}
	img.URL = ImageURLPrefix + img.ID

	path := s.imagePath(userID, img.ID)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return Image{}, fmt.Errorf("create image dir: %w", err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return Image{}, fmt.Errorf("write image: %w", err)
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO images (id, user_id, mime, size, created_at) VALUES (?, ?, ?, ?, ?)`,
		img.ID, userID, img.MIME, img.Size, time.Now().UnixMilli())
	if err != nil {
		os.Remove(path)
		return Image{}, err
	}
	return img, nil
}

// OpenImage returns one of the user's images and the path of its file.
func (s *Store) OpenImage(ctx context.Context, userID int64, id string) (Image, string, error) {
	img := Image{ID: id, URL: ImageURLPrefix + id}
	err := s.db.QueryRowContext(ctx, `SELECT mime, size FROM images WHERE id = ? AND user_id = ?`, id, userID).
		Scan(&img.MIME, &img.Size)
	if errors.Is(err, sql.ErrNoRows) {
		return img, "", ErrNotFound
	}
	return img, s.imagePath(userID, id), err
}

// PurgeImages removes images uploaded before the cutoff that no document
// of their owner refers to, including documents in the trash, and reports
// how many there were.
func (s *Store) PurgeImages(ctx context.Context, before time.Time) (int64, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, user_id FROM images AS i
		WHERE created_at < ? AND NOT EXISTS (
			SELECT 1 FROM documents AS d
			WHERE d.user_id = i.user_id AND instr(d.content, ? || i.id) > 0
		)`, before.UnixMilli(), ImageURLPrefix)
	if err != nil {
		return 0, err
	}
	type orphan struct {
		id     string
		userID int64
	}
	var orphans []orphan
	for rows.Next() {
		var o orphan
		if err := rows.Scan(&o.id, &o.userID); err != nil {
			rows.Close()
			return 0, err
		}
		orphans = append(orphans, o)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	var n int64
	for _, o := range orphans {
		if err := os.Remove(s.imagePath(o.userID, o.id)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return n, fmt.Errorf("remove image: %w", err)
		}
		if _, err := s.db.ExecContext(ctx, `DELETE FROM images WHERE id = ?`, o.id); err != nil {
			return n, err
		}
		n++
	}
	return n, nil
}

func (s *Store) imagePath(userID int64, id string) string {
	return filepath.Join(s.dir, "images", strconv.FormatInt(userID, 10), id)
}

// newImageID returns 16 random lowercase base-32 characters (80 bits), so
// the ID is safe in file names, URLs and SQL patterns.
func newImageID() string {
	const alphabet = "abcdefghijklmnopqrstuvwxyz234567"
	b := make([]byte, 16)
	rand.Read(b)
	for i := range b {
		b[i] = alphabet[b[i]%32]
	}
	return string(b)
}
