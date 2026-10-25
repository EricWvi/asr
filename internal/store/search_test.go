package store

import (
	"context"
	"testing"
	"time"
)

func TestSearchDocuments(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice, bob := newUser(t, s, "alice"), newUser(t, s, "bob")
	add := func(uid int64, title, content string) int64 {
		t.Helper()
		d, err := s.CreateDocument(ctx, uid, title, "mint")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.UpdateDocument(ctx, uid, d.ID, DocumentPatch{Content: &content}); err != nil {
			t.Fatal(err)
		}
		time.Sleep(2 * time.Millisecond) // distinct updated_at
		return d.ID
	}
	trip := add(alice, "周末出行", "## 要带的东西\n\n- [ ] 给**猫**留够两天的粮和水\n- [ ] 下载离线地图")
	shop := add(alice, "采购清单", "咖啡豆、滤纸、猫砂")
	cat := add(alice, "猫的体检", "预约周六上午")
	code := add(alice, "代码备忘", "Device pairing uses 100% of OIDC_sub")
	add(bob, "bob 的猫", "离线地图")
	gone := add(alice, "已删除", "离线地图也在这里")
	if err := s.DeleteDocument(ctx, alice, gone); err != nil {
		t.Fatal(err)
	}

	ids := func(q string) []int64 {
		t.Helper()
		docs, err := s.SearchDocuments(ctx, alice, q)
		if err != nil {
			t.Fatalf("search %q: %v", q, err)
		}
		var out []int64
		for _, d := range docs {
			out = append(out, d.ID)
		}
		return out
	}
	for _, tc := range []struct {
		q    string
		want []int64
	}{
		// One character: LIKE; a title hit first, then by last update.
		{"猫", []int64{cat, shop, trip}},
		{"离线地图", []int64{trip}},        // trigram index; bob's and deleted ones left out
		{"猫 地图", []int64{trip}},        // every word must appear
		{"device OIDC", []int64{code}}, // case-insensitive
		{"100%", []int64{code}},        // LIKE wildcards are literal
		{"_sub", []int64{code}},
		{`"猫"`, nil},
		{"   ", nil},
		{"露营", nil},
	} {
		got := ids(tc.q)
		if len(got) != len(tc.want) {
			t.Errorf("search %q = %v, want %v", tc.q, got, tc.want)
			continue
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Errorf("search %q = %v, want %v", tc.q, got, tc.want)
				break
			}
		}
	}

	// The index follows edits.
	edited := "改成去露营"
	if _, err := s.UpdateDocument(ctx, alice, shop, DocumentPatch{Content: &edited}); err != nil {
		t.Fatal(err)
	}
	if got := ids("去露营"); len(got) != 1 || got[0] != shop {
		t.Errorf("after edit: %v", got)
	}
	if got := ids("咖啡豆"); len(got) != 0 {
		t.Errorf("old text still found: %v", got)
	}

	// Snippets show the hit's line without marks.
	docs, _ := s.SearchDocuments(ctx, alice, "猫")
	for _, d := range docs {
		switch d.ID {
		case trip:
			if d.Snippet != "给猫留够两天的粮和水" {
				t.Errorf("trip snippet = %q", d.Snippet)
			}
		case cat:
			if d.Snippet != "预约周六上午" {
				t.Errorf("title-only snippet = %q", d.Snippet)
			}
		}
	}
}

func TestHitSnippetCutsLongLines(t *testing.T) {
	line := "前面有很多很多很多很多很多字，然后是关键词，后面还有字"
	got := hitSnippet(line, []string{"关键词"})
	if got != "…多很多很多字，然后是关键词，后面还有字" {
		t.Errorf("snippet = %q", got)
	}
}
