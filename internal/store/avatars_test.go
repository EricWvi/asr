package store

import (
	"bytes"
	"context"
	"errors"
	"os"
	"testing"
)

func TestAvatarReplaceAndDelete(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice, bob := newUser(t, s, "alice"), newUser(t, s, "bob")

	if u, err := s.User(ctx, alice); err != nil || u.Avatar != "" || u.AvatarURL() != "" {
		t.Fatalf("new user: %+v, err = %v", u, err)
	}
	first, err := s.SetAvatar(ctx, alice, pngHeader)
	if err != nil || len(first) != 16 {
		t.Fatalf("set: id = %q, err = %v", first, err)
	}
	firstPath, err := s.AvatarPath(ctx, alice, first)
	if data, _ := os.ReadFile(firstPath); err != nil || !bytes.Equal(data, pngHeader) {
		t.Fatalf("path = %q, err = %v", firstPath, err)
	}
	if u, _ := s.User(ctx, alice); u.AvatarURL() != AvatarURLPrefix+first {
		t.Fatalf("user after set: %+v", u)
	}

	// Replacing removes the old file and retires its URL.
	second, _ := s.SetAvatar(ctx, alice, pngHeader)
	if _, err := os.Stat(firstPath); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("old avatar file still there: err = %v", err)
	}
	if _, err := s.AvatarPath(ctx, alice, first); !errors.Is(err, ErrNotFound) {
		t.Errorf("old id: err = %v", err)
	}
	if _, err := s.AvatarPath(ctx, bob, second); !errors.Is(err, ErrNotFound) {
		t.Errorf("bob opens alice's avatar: err = %v", err)
	}
	if u, _ := s.User(ctx, alice); u.Avatar != second {
		t.Fatalf("user after replace: %+v", u)
	}

	secondPath, _ := s.AvatarPath(ctx, alice, second)
	if err := s.DeleteAvatar(ctx, alice); err != nil {
		t.Fatal(err)
	}
	if u, _ := s.User(ctx, alice); u.Avatar != "" {
		t.Errorf("user after delete: %+v", u)
	}
	if _, err := os.Stat(secondPath); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("deleted avatar file still there: err = %v", err)
	}
	if err := s.DeleteAvatar(ctx, alice); err != nil {
		t.Errorf("delete again: %v", err)
	}
	if _, err := s.AvatarPath(ctx, alice, ""); !errors.Is(err, ErrNotFound) {
		t.Errorf("empty id without avatar: err = %v", err)
	}

	for _, data := range [][]byte{nil, []byte("<svg xmlns='http://www.w3.org/2000/svg'/>"), make([]byte, MaxAvatarSize+1)} {
		if _, err := s.SetAvatar(ctx, alice, data); !errors.Is(err, ErrInvalid) {
			t.Errorf("set(%.20q): err = %v, want ErrInvalid", data, err)
		}
	}
}
