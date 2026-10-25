package store

import (
	"context"
	"slices"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// Limits on searches.
const (
	maxSearchWords   = 8
	maxSearchResults = 50
	// The trigram index only finds words of three characters or more;
	// shorter ones fall back to a scan with LIKE.
	trigram = 3
	// How much of a hit's line a result shows, in runes.
	hitBefore, hitAfter = 10, 60
)

// SearchDocuments finds the user's documents whose title or content holds
// every word of the query, case-insensitively. Documents with a word in
// the title come first, then the rest, each by last update. A result's
// Snippet is the line around the first hit in the content, without
// Markdown marks, or the usual snippet if only the title matched.
func (s *Store) SearchDocuments(ctx context.Context, userID int64, query string) ([]Document, error) {
	words := strings.Fields(query)
	if len(words) > maxSearchWords {
		words = words[:maxSearchWords]
	}
	if len(words) == 0 {
		return []Document{}, nil
	}

	where := []string{"d.user_id = ?", "d.deleted_at IS NULL"}
	args := []any{userID}
	var phrases []string
	for _, w := range words {
		if utf8.RuneCountInString(w) >= trigram {
			phrases = append(phrases, `"`+strings.ReplaceAll(w, `"`, `""`)+`"`)
			continue
		}
		like := "%" + likeEscaper.Replace(w) + "%"
		where = append(where, `(d.title LIKE ? ESCAPE '\' OR d.content LIKE ? ESCAPE '\')`)
		args = append(args, like, like)
	}
	if len(phrases) > 0 {
		where = append(where, "d.id IN (SELECT rowid FROM documents_fts WHERE documents_fts MATCH ?)")
		args = append(args, strings.Join(phrases, " AND "))
	}

	rows, err := s.db.QueryContext(ctx, `
		SELECT d.id, d.title, d.color, d.content, d.created_at, d.updated_at
		FROM documents d
		WHERE `+strings.Join(where, " AND ")+`
		ORDER BY d.updated_at DESC, d.id DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var inTitle, inContent []Document
	for rows.Next() {
		var d Document
		var content string
		var created, updated int64
		if err := rows.Scan(&d.ID, &d.Title, &d.Color, &content, &created, &updated); err != nil {
			return nil, err
		}
		d.CreatedAt, d.UpdatedAt = time.UnixMilli(created), time.UnixMilli(updated)
		d.Snippet = hitSnippet(content, words)
		if slices.ContainsFunc(words, func(w string) bool { return indexFold(d.Title, w) >= 0 }) {
			inTitle = append(inTitle, d)
		} else {
			inContent = append(inContent, d)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	docs := append(inTitle, inContent...)
	if docs == nil {
		docs = []Document{}
	}
	if len(docs) > maxSearchResults {
		docs = docs[:maxSearchResults]
	}
	return docs, nil
}

var likeEscaper = strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)

// hitSnippet is the text around the first hit of any word in content.
func hitSnippet(content string, words []string) string {
	fence := false
	for line := range strings.Lines(content) {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "```") || strings.HasPrefix(trimmed, "~~~") {
			fence = !fence
			continue
		}
		text := trimmed
		if !fence {
			text = blockMark.ReplaceAllString(text, "")
			text = link.ReplaceAllString(text, "$1")
			text = strings.TrimSpace(inlineMark.ReplaceAllString(text, ""))
		}
		for _, w := range words {
			at := indexFold(text, w)
			if at < 0 {
				continue
			}
			runes := []rune(text)
			from := max(0, at-hitBefore)
			to := min(len(runes), at+utf8.RuneCountInString(w)+hitAfter)
			out := string(runes[from:to])
			if from > 0 {
				out = "…" + out
			}
			return out
		}
	}
	return Snippet(content)
}

// indexFold is the rune index of the first case-insensitive occurrence of
// word in text, or -1.
func indexFold(text, word string) int {
	t := []rune(strings.Map(unicode.ToLower, text))
	w := []rune(strings.Map(unicode.ToLower, word))
	if len(w) == 0 {
		return -1
	}
	for i := 0; i+len(w) <= len(t); i++ {
		if slices.Equal(t[i:i+len(w)], w) {
			return i
		}
	}
	return -1
}
