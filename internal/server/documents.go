package server

import (
	"net/http"
	"strconv"
	"unicode/utf8"

	"asr/internal/auth"
	"asr/internal/store"
)

// A patch can carry a whole document; JSON escaping may inflate it.
const maxPatchBody = 4 * store.MaxContentLen

func (s *server) listDocuments(w http.ResponseWriter, r *http.Request) {
	docs, err := s.store.ListDocuments(r.Context(), auth.User(r.Context()).ID)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, docs)
}

func (s *server) createDocument(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Title string `json:"title"`
		Color string `json:"color"`
	}
	if !decode(w, r, &body, defaultBody) {
		return
	}
	d, err := s.store.CreateDocument(r.Context(), auth.User(r.Context()).ID, body.Title, body.Color)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, d)
}

func (s *server) getDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r)
	if !ok {
		return
	}
	d, err := s.store.GetDocument(r.Context(), auth.User(r.Context()).ID, id)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

func (s *server) updateDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r)
	if !ok {
		return
	}
	var p store.DocumentPatch
	if !decode(w, r, &p, maxPatchBody) {
		return
	}
	d, err := s.store.UpdateDocument(r.Context(), auth.User(r.Context()).ID, id, p)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

func (s *server) deleteDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r)
	if !ok {
		return
	}
	if err := s.store.DeleteDocument(r.Context(), auth.User(r.Context()).ID, id); err != nil {
		writeStoreError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) restoreDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r)
	if !ok {
		return
	}
	if err := s.store.RestoreDocument(r.Context(), auth.User(r.Context()).ID, id); err != nil {
		writeStoreError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// searchDocuments answers GET /api/search?q=... with matching documents,
// each with a snippet around its first hit.
func (s *server) searchDocuments(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	if utf8.RuneCountInString(q) > maxQuery {
		writeError(w, http.StatusBadRequest, "搜索内容太长")
		return
	}
	docs, err := s.store.SearchDocuments(r.Context(), auth.User(r.Context()).ID, q)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, docs)
}

const maxQuery = 200 // runes

func pathID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeError(w, http.StatusNotFound, "内容不存在或已被删除")
		return 0, false
	}
	return id, true
}
