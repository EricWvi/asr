package server

import (
	"errors"
	"io"
	"net/http"
	"os"

	"asr/internal/auth"
	"asr/internal/store"
)

// setAvatar takes the raw image bytes as the request body; the web app
// sends the cropped square.
func (s *server) setAvatar(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, store.MaxAvatarSize))
	if errors.As(err, new(*http.MaxBytesError)) {
		writeError(w, http.StatusRequestEntityTooLarge, "头像不能超过 1 MB")
		return
	} else if err != nil {
		writeError(w, http.StatusBadRequest, "上传中断")
		return
	}
	id, err := s.store.SetAvatar(r.Context(), auth.User(r.Context()).ID, data)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"avatar_url": store.User{Avatar: id}.AvatarURL()})
}

func (s *server) deleteAvatar(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeleteAvatar(r.Context(), auth.User(r.Context()).ID); err != nil {
		writeStoreError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) getAvatar(w http.ResponseWriter, r *http.Request) {
	path, err := s.store.AvatarPath(r.Context(), auth.User(r.Context()).ID, r.PathValue("id"))
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
	// A new avatar gets a new ID, so this URL never changes content.
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", info.ModTime(), f)
}
