// Package server exposes the store over a JSON API and serves the
// embedded frontend.
package server

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"strings"

	"asr/internal/auth"
	"asr/internal/store"
)

// Options are the settings the server passes on to the frontend.
type Options struct {
	Version string
}

type server struct {
	store *store.Store
	opts  Options
}

// New returns the HTTP handler for auth, the API and the frontend in web.
// The API requires a signed-in user; the app shell does not, so it can load
// offline and send the user to the login page.
func New(st *store.Store, authn *auth.Service, web fs.FS, opts Options) (http.Handler, error) {
	s := &server{store: st, opts: opts}
	static, err := newStatic(web)
	if err != nil {
		return nil, err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/version", s.getVersion)
	authn.Register(mux)

	// Everything registered on private is only reachable with a session.
	private := http.NewServeMux()
	mux.Handle("/api/", authn.Require(private))
	mux.Handle("/", static)

	private.HandleFunc("GET /api/me", s.getMe)
	private.HandleFunc("GET /api/documents", s.listDocuments)
	private.HandleFunc("POST /api/documents", s.createDocument)
	private.HandleFunc("GET /api/documents/{id}", s.getDocument)
	private.HandleFunc("PATCH /api/documents/{id}", s.updateDocument)
	private.HandleFunc("DELETE /api/documents/{id}", s.deleteDocument)
	private.HandleFunc("POST /api/documents/{id}/restore", s.restoreDocument)
	private.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "接口不存在")
	})
	return mux, nil
}

func (s *server) getVersion(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"version": s.opts.Version})
}

func (s *server) getMe(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, auth.User(r.Context()))
}

func writeStoreError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, store.ErrInvalid):
		writeError(w, http.StatusBadRequest, detail(err, store.ErrInvalid))
	case errors.Is(err, store.ErrConflict):
		writeError(w, http.StatusConflict, detail(err, store.ErrConflict))
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "内容不存在或已被删除")
	default:
		slog.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
		writeError(w, http.StatusInternalServerError, "服务器内部错误")
	}
}

// detail strips the sentinel prefix from a wrapped store error.
func detail(err, sentinel error) string {
	return strings.TrimPrefix(err.Error(), sentinel.Error()+": ")
}

// defaultBody caps request bodies that carry no document content.
const defaultBody = 256 << 10

func decode(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit)).Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "请求格式不正确")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
