package store

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"
)

func ptr[T any](v T) *T { return &v }

func TestCreateDocumentDefaults(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	uid := newUser(t, s, "alice")

	d, err := s.CreateDocument(ctx, uid, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := time.ParseInLocation("2006/01/02 15:04", d.Title, time.Local); err != nil {
		t.Errorf("default title %q is not a local time: %v", d.Title, err)
	}
	if !slices.Contains(Colors, d.Color) {
		t.Errorf("color = %q", d.Color)
	}

	d, err = s.CreateDocument(ctx, uid, "  周末  ", "mint")
	if err != nil || d.Title != "周末" || d.Color != "mint" {
		t.Fatalf("doc = %+v, err = %v", d, err)
	}
	for _, tc := range []struct{ title, color string }{
		{"x", "red"},
		{strings.Repeat("长", MaxTitleLen+1), ""},
		{"a\nb", ""},
	} {
		if _, err := s.CreateDocument(ctx, uid, tc.title, tc.color); !errors.Is(err, ErrInvalid) {
			t.Errorf("create(%q, %q): err = %v, want ErrInvalid", tc.title, tc.color, err)
		}
	}
}

func TestDocumentsAreScopedToTheirUser(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice, bob := newUser(t, s, "alice"), newUser(t, s, "bob")
	d, err := s.CreateDocument(ctx, alice, "私密", "rose")
	if err != nil {
		t.Fatal(err)
	}

	if docs, _ := s.ListDocuments(ctx, bob); len(docs) != 0 {
		t.Fatalf("bob lists %+v", docs)
	}
	if _, err := s.GetDocument(ctx, bob, d.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("get: err = %v", err)
	}
	if _, err := s.UpdateDocument(ctx, bob, d.ID, DocumentPatch{Content: ptr("hacked")}); !errors.Is(err, ErrNotFound) {
		t.Errorf("update: err = %v", err)
	}
	if err := s.DeleteDocument(ctx, bob, d.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("delete: err = %v", err)
	}
	if got, err := s.GetDocument(ctx, alice, d.ID); err != nil || got.Content != "" {
		t.Fatalf("alice's doc = %+v, err = %v", got, err)
	}
}

func TestUpdateDocumentOrdersByEdits(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	uid := newUser(t, s, "alice")
	first, _ := s.CreateDocument(ctx, uid, "first", "butter")
	time.Sleep(2 * time.Millisecond)
	second, _ := s.CreateDocument(ctx, uid, "second", "sky")
	time.Sleep(2 * time.Millisecond)

	ids := func() []int64 {
		docs, err := s.ListDocuments(ctx, uid)
		if err != nil {
			t.Fatal(err)
		}
		var ids []int64
		for _, d := range docs {
			ids = append(ids, d.ID)
		}
		return ids
	}
	if got := ids(); !slices.Equal(got, []int64{second.ID, first.ID}) {
		t.Fatalf("order = %v", got)
	}

	// Recolouring does not reorder.
	d, err := s.UpdateDocument(ctx, uid, first.ID, DocumentPatch{Color: ptr("mint")})
	if err != nil || d.Color != "mint" || !d.UpdatedAt.Equal(first.UpdatedAt) {
		t.Fatalf("recolour: %+v, err = %v", d, err)
	}
	if got := ids(); got[0] != second.ID {
		t.Fatalf("recolour reordered: %v", got)
	}

	d, err = s.UpdateDocument(ctx, uid, first.ID, DocumentPatch{Content: ptr("## 周末\n\n去**杭州**")})
	if err != nil || d.Snippet != "周末" || d.Content != "" {
		t.Fatalf("edit: %+v, err = %v", d, err)
	}
	if got := ids(); got[0] != first.ID {
		t.Fatalf("edit did not move to top: %v", got)
	}
	full, _ := s.GetDocument(ctx, uid, first.ID)
	if full.Content != "## 周末\n\n去**杭州**" || full.Title != "first" {
		t.Fatalf("full = %+v", full)
	}

	if _, err := s.UpdateDocument(ctx, uid, first.ID, DocumentPatch{}); !errors.Is(err, ErrInvalid) {
		t.Errorf("empty patch: err = %v", err)
	}
	if _, err := s.UpdateDocument(ctx, uid, first.ID, DocumentPatch{Title: ptr(" ")}); !errors.Is(err, ErrInvalid) {
		t.Errorf("blank title: err = %v", err)
	}
	big := strings.Repeat("x", MaxContentLen+1)
	if _, err := s.UpdateDocument(ctx, uid, first.ID, DocumentPatch{Content: &big}); !errors.Is(err, ErrInvalid) {
		t.Errorf("huge content: err = %v", err)
	}
}

func TestDeleteRestoreAndPurge(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	uid := newUser(t, s, "alice")
	d, _ := s.CreateDocument(ctx, uid, "temp", "peach")

	if err := s.DeleteDocument(ctx, uid, d.ID); err != nil {
		t.Fatal(err)
	}
	if docs, _ := s.ListDocuments(ctx, uid); len(docs) != 0 {
		t.Fatalf("deleted doc still listed: %+v", docs)
	}
	if _, err := s.GetDocument(ctx, uid, d.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("get deleted: err = %v", err)
	}
	if err := s.DeleteDocument(ctx, uid, d.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("delete twice: err = %v", err)
	}
	if err := s.RestoreDocument(ctx, uid, d.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.RestoreDocument(ctx, uid, d.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("restore a live doc: err = %v", err)
	}

	s.DeleteDocument(ctx, uid, d.ID)
	if n, err := s.PurgeDeleted(ctx, time.Now().Add(-time.Hour)); err != nil || n != 0 {
		t.Fatalf("purge before cutoff: n = %d, err = %v", n, err)
	}
	if n, err := s.PurgeDeleted(ctx, time.Now().Add(time.Second)); err != nil || n != 1 {
		t.Fatalf("purge: n = %d, err = %v", n, err)
	}
	if err := s.RestoreDocument(ctx, uid, d.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("restore purged: err = %v", err)
	}
}

func TestSnippet(t *testing.T) {
	for _, tc := range []struct{ in, want string }{
		{"", ""},
		{"\n\n普通一行\n第二行", "普通一行"},
		{"## 周末出行\n正文", "周末出行"},
		{"---\n> 引用 **加粗** ==高亮==", "引用 加粗 高亮"},
		{"- [x] 买菜", "买菜"},
		{"1. 第一步", "第一步"},
		{"```go\nfmt.Println()\n```\n代码后", "代码后"},
		{"看 [文档](https://x.test) 和 ![](a.png)", "看 文档 和"},
		{"`code` 行", "code 行"},
		{strings.Repeat("字", 100), strings.Repeat("字", snippetLen)},
	} {
		if got := Snippet(tc.in); got != tc.want {
			t.Errorf("Snippet(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestTidyRangesKeptWithContent(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	uid := newUser(t, s, "alice")
	d, err := s.CreateDocument(ctx, uid, "笔记", "mint")
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := s.GetDocument(ctx, uid, d.ID); len(got.TidyRanges) != 0 {
		t.Fatalf("new document has ranges %v", got.TidyRanges)
	}

	// Offsets count UTF-16 units: "😀" is two, "说话" two.
	content := "😀说话 hello"
	ranges := []Range{{0, 2}, {2, 4}, {5, 10}}
	if _, err := s.UpdateDocument(ctx, uid, d.ID, DocumentPatch{Content: &content, TidyRanges: &ranges}); err != nil {
		t.Fatal(err)
	}
	got, err := s.GetDocument(ctx, uid, d.ID)
	if err != nil || !slices.Equal(got.TidyRanges, ranges) {
		t.Fatalf("ranges = %v, err = %v", got.TidyRanges, err)
	}

	// Ranges alone may change without moving the document up.
	empty := []Range{}
	before := got.UpdatedAt
	time.Sleep(2 * time.Millisecond)
	if u, err := s.UpdateDocument(ctx, uid, d.ID, DocumentPatch{TidyRanges: &empty}); err != nil || !u.UpdatedAt.Equal(before) {
		t.Fatalf("update = %+v, err = %v", u, err)
	}
	if got, _ := s.GetDocument(ctx, uid, d.ID); len(got.TidyRanges) != 0 {
		t.Fatalf("ranges = %v", got.TidyRanges)
	}

	for _, bad := range [][]Range{{{3, 3}}, {{-1, 2}}, {{4, 6}, {2, 3}}, {{0, 3}, {2, 4}}, {{0, 11}}} {
		if _, err := s.UpdateDocument(ctx, uid, d.ID, DocumentPatch{Content: &content, TidyRanges: &bad}); !errors.Is(err, ErrInvalid) {
			t.Errorf("ranges %v: err = %v, want ErrInvalid", bad, err)
		}
	}
}
