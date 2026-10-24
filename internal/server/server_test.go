package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"

	"asr/internal/auth"
	"asr/internal/store"
)

const origin = "https://asr.test"

// testServer is the handler plus a store for minting sessions.
type testServer struct {
	http.Handler
	store *store.Store
	// session is sent as the cookie on every request made with do.
	session string
}

func newTest(t *testing.T) *testServer {
	t.Helper()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	// The provider is discovered lazily, so these tests never reach it.
	authn := auth.New(st, auth.Config{PublicURL: origin, Issuer: "https://idp.invalid", ClientID: "x", ClientSecret: "y"})
	web := fstest.MapFS{
		"index.html":        {Data: []byte("<!doctype html><title>asr</title>")},
		"assets/app-abc.js": {Data: []byte("console.log(1)")},
		"sw.js":             {Data: []byte("self.addEventListener('fetch',()=>{})")},
		".gitkeep":          {Data: nil},
	}
	h, err := New(st, authn, web, Options{Version: "test"})
	if err != nil {
		t.Fatal(err)
	}
	ts := &testServer{Handler: h, store: st}
	ts.session = ts.signIn(t, "alice")
	return ts
}

// signIn creates a user and returns a session token for it.
func (ts *testServer) signIn(t *testing.T, subject string) string {
	t.Helper()
	ctx := context.Background()
	u, err := ts.store.UpsertUser(ctx, store.Identity{Issuer: "https://idp.test", Subject: subject, Name: subject})
	if err != nil {
		t.Fatal(err)
	}
	token, err := ts.store.CreateSession(ctx, u.ID)
	if err != nil {
		t.Fatal(err)
	}
	return token
}

func do(t *testing.T, ts *testServer, method, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if ts.session != "" {
		req.AddCookie(&http.Cookie{Name: "asr_session", Value: ts.session})
	}
	rec := httptest.NewRecorder()
	ts.ServeHTTP(rec, req)
	return rec
}

func TestMeReturnsTheSignedInUser(t *testing.T) {
	h := newTest(t)
	rec := do(t, h, "GET", "/api/me", "")
	var u store.User
	if rec.Code != http.StatusOK || json.Unmarshal(rec.Body.Bytes(), &u) != nil || u.Name != "alice" {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body)
	}
	h.session = h.signIn(t, "bob")
	json.Unmarshal(do(t, h, "GET", "/api/me", "").Body.Bytes(), &u)
	if u.Name != "bob" {
		t.Fatalf("bob sees %+v", u)
	}
}

func TestStaticCaching(t *testing.T) {
	h := newTest(t)
	h.session = "" // the app shell must load without signing in
	for _, tc := range []struct {
		path, cache string
		code        int
	}{
		{"/", "no-cache", 200},
		{"/sw.js", "no-cache", 200},
		{"/assets/app-abc.js", "public, max-age=31536000, immutable", 200},
		{"/some/page", "no-cache", 200},
		{"/assets/missing.js", "", 404},
		{"/.gitkeep", "", 404},
	} {
		rec := do(t, h, "GET", tc.path, "")
		if rec.Code != tc.code || rec.Header().Get("Cache-Control") != tc.cache {
			t.Errorf("%s: status = %d, Cache-Control = %q", tc.path, rec.Code, rec.Header().Get("Cache-Control"))
		}
	}

	rec := do(t, h, "GET", "/", "")
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set("If-None-Match", rec.Header().Get("ETag"))
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotModified {
		t.Fatalf("revalidation: status = %d", rec.Code)
	}
}

func TestAPIRequiresSession(t *testing.T) {
	h := newTest(t)
	for _, session := range []string{"", "forged-token"} {
		h.session = session
		for _, path := range []string{"/api/me", "/api/nope"} {
			if rec := do(t, h, "GET", path, ""); rec.Code != http.StatusUnauthorized {
				t.Errorf("session %q, GET %s: status = %d", session, path, rec.Code)
			}
		}
	}
	h.session = ""
	if rec := do(t, h, "GET", "/api/version", ""); rec.Code != http.StatusOK {
		t.Errorf("version: status = %d", rec.Code)
	}
}

func TestCrossOriginWritesAreRejected(t *testing.T) {
	h := newTest(t)
	for _, tc := range []struct {
		origin string
		code   int
	}{
		{"https://evil.test", http.StatusForbidden},
		{"https://other.asr.test", http.StatusForbidden},
		{origin, http.StatusNotFound},
		{"", http.StatusNotFound},
	} {
		// Passing the origin check reaches the API's not-found handler.
		req := httptest.NewRequest("POST", "/api/nope", strings.NewReader(`{}`))
		req.AddCookie(&http.Cookie{Name: "asr_session", Value: h.session})
		if tc.origin != "" {
			req.Header.Set("Origin", tc.origin)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != tc.code {
			t.Errorf("Origin %q: status = %d, want %d", tc.origin, rec.Code, tc.code)
		}
	}
}

func TestLogoutEndsOnlyThisSession(t *testing.T) {
	h := newTest(t)
	other := h.signIn(t, "alice") // same user, another browser

	rec := do(t, h, "POST", "/auth/logout", "")
	if rec.Code != http.StatusNoContent {
		t.Fatalf("logout: status = %d", rec.Code)
	}
	if c := rec.Result().Cookies(); len(c) != 1 || c[0].Name != "asr_session" || c[0].MaxAge >= 0 {
		t.Fatalf("logout cookies = %+v", c)
	}
	if rec := do(t, h, "GET", "/api/me", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("after logout: status = %d", rec.Code)
	}
	h.session = other
	if rec := do(t, h, "GET", "/api/me", ""); rec.Code != http.StatusOK {
		t.Fatalf("other browser: status = %d", rec.Code)
	}
}

func TestDocumentsAPI(t *testing.T) {
	h := newTest(t)
	rec := do(t, h, "POST", "/api/documents", `{"title":"2026/10/24 14:32","color":"sky"}`)
	var d store.Document
	if rec.Code != http.StatusCreated || json.Unmarshal(rec.Body.Bytes(), &d) != nil || d.ID == 0 || d.Color != "sky" {
		t.Fatalf("create: status = %d, body = %s", rec.Code, rec.Body)
	}
	path := fmt.Sprintf("/api/documents/%d", d.ID)

	rec = do(t, h, "PATCH", path, `{"content":"# 标题\n正文"}`)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"snippet":"标题"`) {
		t.Fatalf("patch: status = %d, body = %s", rec.Code, rec.Body)
	}
	rec = do(t, h, "GET", path, "")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"content":"# 标题\n正文"`) {
		t.Fatalf("get: status = %d, body = %s", rec.Code, rec.Body)
	}
	if rec := do(t, h, "PATCH", path, `{"color":"red"}`); rec.Code != http.StatusBadRequest {
		t.Errorf("bad color: status = %d", rec.Code)
	}

	// Another user cannot see or touch it.
	alice := h.session
	h.session = h.signIn(t, "bob")
	if rec := do(t, h, "GET", "/api/documents", ""); rec.Body.String() != "[]\n" {
		t.Errorf("bob's list = %s", rec.Body)
	}
	for _, m := range []string{"GET", "PATCH", "DELETE"} {
		if rec := do(t, h, m, path, `{"title":"x"}`); rec.Code != http.StatusNotFound {
			t.Errorf("bob %s: status = %d", m, rec.Code)
		}
	}
	h.session = alice

	if rec := do(t, h, "DELETE", path, ""); rec.Code != http.StatusNoContent {
		t.Fatalf("delete: status = %d", rec.Code)
	}
	if rec := do(t, h, "GET", path, ""); rec.Code != http.StatusNotFound {
		t.Errorf("get deleted: status = %d", rec.Code)
	}
	if rec := do(t, h, "POST", path+"/restore", ""); rec.Code != http.StatusNoContent {
		t.Fatalf("restore: status = %d", rec.Code)
	}
	var docs []store.Document
	json.Unmarshal(do(t, h, "GET", "/api/documents", "").Body.Bytes(), &docs)
	if len(docs) != 1 || docs[0].ID != d.ID {
		t.Fatalf("list = %+v", docs)
	}
	if rec := do(t, h, "GET", "/api/documents/abc", ""); rec.Code != http.StatusNotFound {
		t.Errorf("bad id: status = %d", rec.Code)
	}
}
