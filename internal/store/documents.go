package store

import (
	"context"
	"database/sql"
	"errors"
	"math/rand/v2"
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

// Colors are the pastels a document can take, in palette order.
var Colors = []string{"butter", "peach", "rose", "periwinkle", "sky", "mint"}

// Limits on document fields.
const (
	MaxTitleLen   = 200     // runes
	MaxContentLen = 1 << 20 // bytes
	snippetLen    = 80      // runes
)

// TrashRetention is how long a deleted document can still be restored
// before PurgeDeleted removes it for good.
const TrashRetention = 30 * 24 * time.Hour

// Document is a Markdown note. Content is omitted from listings, which
// carry Snippet instead.
type Document struct {
	ID        int64     `json:"id"`
	Title     string    `json:"title"`
	Color     string    `json:"color"`
	Content   string    `json:"content,omitempty"`
	Snippet   string    `json:"snippet,omitempty"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// DocumentPatch changes some fields of a document; nil fields are kept.
type DocumentPatch struct {
	Title   *string `json:"title"`
	Color   *string `json:"color"`
	Content *string `json:"content"`
}

// CreateDocument adds a document for the user. An empty title falls back
// to the creation time and an empty color to a random pastel; clients
// pass the title so it reads in the user's own time zone.
func (s *Store) CreateDocument(ctx context.Context, userID int64, title, color string) (Document, error) {
	now := time.Now()
	if strings.TrimSpace(title) == "" {
		title = now.Format("2006/01/02 15:04")
	}
	if color == "" {
		color = Colors[rand.IntN(len(Colors))]
	}
	title, err := checkTitle(title)
	if err != nil {
		return Document{}, err
	}
	if err := checkColor(color); err != nil {
		return Document{}, err
	}
	ms := now.UnixMilli()
	res, err := s.db.ExecContext(ctx, `
		INSERT INTO documents (user_id, title, color, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?)`, userID, title, color, ms, ms)
	if err != nil {
		return Document{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Document{}, err
	}
	at := time.UnixMilli(ms)
	return Document{ID: id, Title: title, Color: color, CreatedAt: at, UpdatedAt: at}, nil
}

// ListDocuments returns the user's documents that are not deleted, most
// recently updated first.
func (s *Store) ListDocuments(ctx context.Context, userID int64) ([]Document, error) {
	// The snippet only needs the start of the content.
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, title, color, substr(content, 1, 400), created_at, updated_at
		FROM documents
		WHERE user_id = ? AND deleted_at IS NULL
		ORDER BY updated_at DESC, id DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	docs := []Document{}
	for rows.Next() {
		var d Document
		var head string
		var created, updated int64
		if err := rows.Scan(&d.ID, &d.Title, &d.Color, &head, &created, &updated); err != nil {
			return nil, err
		}
		d.Snippet = Snippet(head)
		d.CreatedAt, d.UpdatedAt = time.UnixMilli(created), time.UnixMilli(updated)
		docs = append(docs, d)
	}
	return docs, rows.Err()
}

// GetDocument returns one of the user's documents with its content.
func (s *Store) GetDocument(ctx context.Context, userID, id int64) (Document, error) {
	var d Document
	var created, updated int64
	err := s.db.QueryRowContext(ctx, `
		SELECT id, title, color, content, created_at, updated_at
		FROM documents
		WHERE id = ? AND user_id = ? AND deleted_at IS NULL`, id, userID).
		Scan(&d.ID, &d.Title, &d.Color, &d.Content, &created, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return d, ErrNotFound
	}
	d.CreatedAt, d.UpdatedAt = time.UnixMilli(created), time.UnixMilli(updated)
	return d, err
}

// UpdateDocument applies the patch and returns the document without its
// content. The last write wins. Changing the title or content moves the
// document to the top of the list; changing the color does not.
func (s *Store) UpdateDocument(ctx context.Context, userID, id int64, p DocumentPatch) (Document, error) {
	var sets []string
	var args []any
	touch := false
	if p.Title != nil {
		title, err := checkTitle(*p.Title)
		if err != nil {
			return Document{}, err
		}
		sets, args, touch = append(sets, "title = ?"), append(args, title), true
	}
	if p.Color != nil {
		if err := checkColor(*p.Color); err != nil {
			return Document{}, err
		}
		sets, args = append(sets, "color = ?"), append(args, *p.Color)
	}
	if p.Content != nil {
		if len(*p.Content) > MaxContentLen {
			return Document{}, invalid("正文太长")
		}
		sets, args, touch = append(sets, "content = ?"), append(args, *p.Content), true
	}
	if touch {
		sets, args = append(sets, "updated_at = ?"), append(args, time.Now().UnixMilli())
	}
	if len(sets) == 0 {
		return Document{}, invalid("没有要修改的内容")
	}

	var d Document
	var head string
	var created, updated int64
	err := s.db.QueryRowContext(ctx, `
		UPDATE documents SET `+strings.Join(sets, ", ")+`
		WHERE id = ? AND user_id = ? AND deleted_at IS NULL
		RETURNING id, title, color, substr(content, 1, 400), created_at, updated_at`,
		append(args, id, userID)...).
		Scan(&d.ID, &d.Title, &d.Color, &head, &created, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return d, ErrNotFound
	}
	d.Snippet = Snippet(head)
	d.CreatedAt, d.UpdatedAt = time.UnixMilli(created), time.UnixMilli(updated)
	return d, err
}

// DeleteDocument moves the document to the trash, from which
// RestoreDocument can bring it back until PurgeDeleted removes it.
func (s *Store) DeleteDocument(ctx context.Context, userID, id int64) error {
	return s.execOne(ctx, `
		UPDATE documents SET deleted_at = ?
		WHERE id = ? AND user_id = ? AND deleted_at IS NULL`, time.Now().UnixMilli(), id, userID)
}

// RestoreDocument undoes DeleteDocument.
func (s *Store) RestoreDocument(ctx context.Context, userID, id int64) error {
	return s.execOne(ctx, `
		UPDATE documents SET deleted_at = NULL
		WHERE id = ? AND user_id = ? AND deleted_at IS NOT NULL`, id, userID)
}

// PurgeDeleted permanently removes documents deleted before the cutoff and
// reports how many there were.
func (s *Store) PurgeDeleted(ctx context.Context, before time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM documents WHERE deleted_at IS NOT NULL AND deleted_at < ?`, before.UnixMilli())
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// execOne runs a statement that must change exactly one row.
func (s *Store) execOne(ctx context.Context, query string, args ...any) error {
	res, err := s.db.ExecContext(ctx, query, args...)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return ErrNotFound
	}
	return nil
}

func checkTitle(title string) (string, error) {
	title = strings.TrimSpace(title)
	switch {
	case title == "":
		return "", invalid("标题不能为空")
	case utf8.RuneCountInString(title) > MaxTitleLen:
		return "", invalid("标题太长")
	case strings.ContainsAny(title, "\r\n"):
		return "", invalid("标题不能换行")
	}
	return title, nil
}

func checkColor(color string) error {
	if !slices.Contains(Colors, color) {
		return invalid("未知的文稿颜色")
	}
	return nil
}

var (
	// Block markers at the start of a line: headings, quotes, list bullets
	// and task boxes.
	blockMark = regexp.MustCompile(`^(?:#{1,6}\s+|>\s*|[-*+]\s+(?:\[[ xX]\]\s+)?|\d+[.)]\s+(?:\[[ xX]\]\s+)?)+`)
	// Inline emphasis, highlight and code marks.
	inlineMark = regexp.MustCompile("\\*\\*|__|~~|==|`")
	// Links and images keep their text.
	link = regexp.MustCompile(`!?\[([^\]]*)\]\([^)]*\)`)
)

// Snippet is the first line of text in Markdown content, without markup,
// for the sidebar. Code fences and rules are skipped.
func Snippet(content string) string {
	fence := false
	for line := range strings.Lines(content) {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "```") || strings.HasPrefix(line, "~~~") {
			fence = !fence
			continue
		}
		if fence || strings.Trim(line, "-*_ ") == "" {
			continue
		}
		line = blockMark.ReplaceAllString(line, "")
		line = link.ReplaceAllString(line, "$1")
		line = strings.TrimSpace(inlineMark.ReplaceAllString(line, ""))
		if line == "" {
			continue
		}
		if r := []rune(line); len(r) > snippetLen {
			line = string(r[:snippetLen])
		}
		return line
	}
	return ""
}
