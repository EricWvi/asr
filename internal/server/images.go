package server

import (
	"errors"
	"io"
	"net/http"
	"os"

	"asr/internal/auth"
	"asr/internal/store"
)

// uploadImage takes the raw image bytes as the request body.
func (s *server) uploadImage(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, store.MaxImageSize))
	if errors.As(err, new(*http.MaxBytesError)) {
		writeError(w, http.StatusRequestEntityTooLarge, "图片不能超过 10 MB")
		return
	} else if err != nil {
		writeError(w, http.StatusBadRequest, "上传中断")
		return
	}
	img, err := s.store.CreateImage(r.Context(), auth.User(r.Context()).ID, data)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, img)
}

func (s *server) getImage(w http.ResponseWriter, r *http.Request) {
	img, path, err := s.store.OpenImage(r.Context(), auth.User(r.Context()).ID, r.PathValue("id"))
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	// An image never changes under its ID.
	w.Header().Set("Content-Type", img.MIME)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", info.ModTime(), f)
}
