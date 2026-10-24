package store

import (
	"bytes"
	"context"
	"errors"
	"os"
	"testing"
	"time"
)

// pngHeader is enough for the format to be sniffed as PNG.
var pngHeader = []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")

func TestCreateImageChecksFormatAndOwner(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice, bob := newUser(t, s, "alice"), newUser(t, s, "bob")

	img, err := s.CreateImage(ctx, alice, pngHeader)
	if err != nil || img.MIME != "image/png" || img.URL != ImageURLPrefix+img.ID || len(img.ID) != 16 {
		t.Fatalf("img = %+v, err = %v", img, err)
	}
	got, path, err := s.OpenImage(ctx, alice, img.ID)
	if err != nil || got.MIME != "image/png" || got.Size != int64(len(pngHeader)) {
		t.Fatalf("open: %+v, err = %v", got, err)
	}
	if data, err := os.ReadFile(path); err != nil || !bytes.Equal(data, pngHeader) {
		t.Fatalf("file = %q, err = %v", data, err)
	}
	if _, _, err := s.OpenImage(ctx, bob, img.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("bob opens alice's image: err = %v", err)
	}

	for _, data := range [][]byte{nil, []byte("<svg xmlns='http://www.w3.org/2000/svg'/>"), []byte("plain text"), make([]byte, MaxImageSize+1)} {
		if _, err := s.CreateImage(ctx, alice, data); !errors.Is(err, ErrInvalid) {
			t.Errorf("create(%.20q): err = %v, want ErrInvalid", data, err)
		}
	}
}

func TestPurgeImagesKeepsReferencedOnes(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice, bob := newUser(t, s, "alice"), newUser(t, s, "bob")
	used, _ := s.CreateImage(ctx, alice, pngHeader)
	trashed, _ := s.CreateImage(ctx, alice, pngHeader)
	orphan, _ := s.CreateImage(ctx, alice, pngHeader)
	// Bob's document mentioning Alice's image does not keep it.
	stolen, _ := s.CreateImage(ctx, alice, pngHeader)

	d, _ := s.CreateDocument(ctx, alice, "a", "mint")
	s.UpdateDocument(ctx, alice, d.ID, DocumentPatch{Content: ptr("![](" + used.URL + ")")})
	d, _ = s.CreateDocument(ctx, alice, "b", "mint")
	s.UpdateDocument(ctx, alice, d.ID, DocumentPatch{Content: ptr("![x](" + trashed.URL + ")")})
	s.DeleteDocument(ctx, alice, d.ID)
	d, _ = s.CreateDocument(ctx, bob, "c", "mint")
	s.UpdateDocument(ctx, bob, d.ID, DocumentPatch{Content: ptr("![](" + stolen.URL + ")")})

	if n, err := s.PurgeImages(ctx, time.Now().Add(-time.Hour)); err != nil || n != 0 {
		t.Fatalf("purge before cutoff: n = %d, err = %v", n, err)
	}
	_, orphanPath, _ := s.OpenImage(ctx, alice, orphan.ID)
	if n, err := s.PurgeImages(ctx, time.Now().Add(time.Second)); err != nil || n != 2 {
		t.Fatalf("purge: n = %d, err = %v", n, err)
	}
	for _, img := range []Image{used, trashed} {
		if _, _, err := s.OpenImage(ctx, alice, img.ID); err != nil {
			t.Errorf("referenced image %s purged: %v", img.ID, err)
		}
	}
	for _, img := range []Image{orphan, stolen} {
		if _, _, err := s.OpenImage(ctx, alice, img.ID); !errors.Is(err, ErrNotFound) {
			t.Errorf("orphan %s kept: err = %v", img.ID, err)
		}
	}
	if _, err := os.Stat(orphanPath); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("orphan file kept: %v", err)
	}
}
