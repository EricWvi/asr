package store

import (
	"context"
	"errors"
	"testing"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func newUser(t *testing.T, s *Store, subject string) int64 {
	t.Helper()
	u, err := s.UpsertUser(context.Background(), Identity{Issuer: "https://idp.test", Subject: subject})
	if err != nil {
		t.Fatal(err)
	}
	return u.ID
}

func TestUpsertUserKeepsIdentityAndRefreshesProfile(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	first, err := s.UpsertUser(ctx, Identity{Issuer: "i", Subject: "s", Name: "Old", Email: "old@x"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.UpsertUser(ctx, Identity{Issuer: "i", Subject: "s", Name: "New", Email: "new@x"})
	if err != nil {
		t.Fatal(err)
	}
	if second != (User{ID: first.ID, Name: "New", Email: "new@x"}) {
		t.Fatalf("second = %+v, first = %+v", second, first)
	}
	other, _ := s.UpsertUser(ctx, Identity{Issuer: "other", Subject: "s"})
	if other.ID == first.ID {
		t.Fatal("same subject from another issuer must be another user")
	}
}

func TestSessionsLastUntilDeleted(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	uid := newUser(t, s, "alice")
	token, err := s.CreateSession(ctx, uid)
	if err != nil {
		t.Fatal(err)
	}
	u, _, err := s.SessionUser(ctx, token)
	if err != nil || u.ID != uid {
		t.Fatalf("user = %+v, err = %v", u, err)
	}
	if err := s.TouchSession(ctx, token); err != nil {
		t.Fatal(err)
	}
	if err := s.DeleteSession(ctx, token); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.SessionUser(ctx, token); !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
	if _, _, err := s.SessionUser(ctx, "made-up"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
}

func TestReopenKeepsData(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	first := newUser(t, s, "alice")
	s.Close()
	s, err = Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if again := newUser(t, s, "alice"); again != first {
		t.Fatalf("user id changed across reopen: %d → %d", first, again)
	}
}
