package server

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"
	"unicode/utf8"

	"asr/internal/auth"
	"asr/internal/tidy"
)

/*
 * POST /api/tidy tidies one stretch of dictated text:
 *
 *   request   {"text":"...","headings":["## ..."],"prefix":"- ","suffix":""}
 *   response  newline-delimited JSON: {"type":"delta","text":"..."} as the
 *             model writes, then {"type":"done"}, or
 *             {"type":"error","message":"..."} if it failed
 *
 * Errors found before streaming starts (not configured, bad request) are
 * plain JSON error responses with an error status instead.
 */

const (
	maxTidyText     = 20000 // runes; five minutes of speech is about 1500
	maxTidyHeadings = 200
	// Thinking takes a few seconds; a long transcript takes longer.
	tidyTimeout = 2 * time.Minute
)

type tidyMessage struct {
	Type    string `json:"type"`
	Text    string `json:"text,omitempty"`
	Message string `json:"message,omitempty"`
}

func (s *server) tidy(w http.ResponseWriter, r *http.Request) {
	if s.opts.Tidy == nil {
		writeError(w, http.StatusServiceUnavailable, "整理未配置")
		return
	}
	var body struct {
		Text     string   `json:"text"`
		Headings []string `json:"headings"`
		Prefix   string   `json:"prefix"`
		Suffix   string   `json:"suffix"`
	}
	if !decode(w, r, &body, defaultBody) {
		return
	}
	switch {
	case body.Text == "":
		writeError(w, http.StatusBadRequest, "没有要整理的文字")
		return
	case utf8.RuneCountInString(body.Text) > maxTidyText:
		writeError(w, http.StatusBadRequest, "要整理的文字太长")
		return
	}
	if len(body.Headings) > maxTidyHeadings {
		body.Headings = body.Headings[len(body.Headings)-maxTidyHeadings:]
	}

	w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	enc := json.NewEncoder(w)
	send := func(m tidyMessage) error {
		if err := enc.Encode(m); err != nil {
			return err
		}
		if flusher != nil {
			flusher.Flush()
		}
		return nil
	}

	ctx, cancel := context.WithTimeout(r.Context(), tidyTimeout)
	defer cancel()
	start := time.Now()
	n := 0
	err := s.opts.Tidy.Stream(ctx, tidy.Request{
		Text:     body.Text,
		Headings: body.Headings,
		Prefix:   body.Prefix,
		Suffix:   body.Suffix,
	}, func(text string) error {
		n += utf8.RuneCountInString(text)
		return send(tidyMessage{Type: "delta", Text: text})
	})
	if err == nil && n == 0 {
		err = errors.New("tidy: empty output")
	}
	user := auth.User(r.Context()).ID
	if err != nil {
		if r.Context().Err() != nil {
			// The user cancelled or left; nobody is listening.
			return
		}
		slog.Warn("tidy failed", "user", user, "runes", utf8.RuneCountInString(body.Text), "err", err)
		send(tidyMessage{Type: "error", Message: tidyErrorMessage(ctx, err)})
		return
	}
	slog.Info("tidied", "user", user, "in", utf8.RuneCountInString(body.Text), "out", n,
		"took", time.Since(start).Round(time.Millisecond))
	send(tidyMessage{Type: "done"})
}

func tidyErrorMessage(ctx context.Context, err error) string {
	var status *tidy.StatusError
	switch {
	case errors.Is(ctx.Err(), context.DeadlineExceeded):
		return "整理超时，请稍后再试"
	case errors.Is(err, tidy.ErrTruncated):
		return "整理结果不完整，没有替换"
	case errors.As(err, &status) && (status.Code == http.StatusUnauthorized || status.Code == http.StatusForbidden):
		return "整理服务的 API Key 无效"
	case errors.As(err, &status) && status.Code == http.StatusTooManyRequests:
		return "整理服务繁忙，请稍后再试"
	default:
		return "整理失败，请稍后再试"
	}
}
