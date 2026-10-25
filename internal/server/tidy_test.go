package server

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"asr/internal/tidy"
)

// newTidyUpstream plays an OpenAI-compatible chat completions API. It
// hands each request body to check and answers with handle.
func newTidyUpstream(t *testing.T, extra map[string]any, check func(body map[string]any), handle func(w http.ResponseWriter)) *tidy.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer test-key" {
			t.Errorf("%s %s, Authorization = %q", r.Method, r.URL.Path, r.Header.Get("Authorization"))
		}
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode request: %v", err)
		}
		if check != nil {
			check(body)
		}
		handle(w)
	}))
	t.Cleanup(srv.Close)
	return tidy.New(srv.URL+"/v1/", "test-model", "test-key", extra)
}

// sse writes chunks as server-sent events; finish is the finish_reason of
// the last chunk, or empty for none.
func sse(w http.ResponseWriter, finish string, deltas ...string) {
	w.Header().Set("Content-Type", "text/event-stream")
	// Thinking comes first and is not part of the result.
	fmt.Fprint(w, `data: {"choices":[{"delta":{"reasoning_content":"想一想"}}]}`+"\n\n")
	for _, d := range deltas {
		data, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": d}}}})
		fmt.Fprintf(w, "data: %s\n\n", data)
	}
	if finish != "" {
		fmt.Fprintf(w, `data: {"choices":[{"delta":{},"finish_reason":%q}]}`+"\n\n", finish)
		fmt.Fprint(w, "data: [DONE]\n\n")
	}
}

// tidyLines posts body to /api/tidy and returns the status and the lines
// of the streamed response.
func tidyLines(t *testing.T, ts *testServer, body string) (int, []tidyMessage) {
	t.Helper()
	rec := do(t, ts, "POST", "/api/tidy", body)
	if rec.Code != http.StatusOK {
		return rec.Code, nil
	}
	var out []tidyMessage
	for line := range strings.Lines(rec.Body.String()) {
		var m tidyMessage
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			t.Fatalf("line %q: %v", line, err)
		}
		out = append(out, m)
	}
	return rec.Code, out
}

func TestTidyStreamsTheResult(t *testing.T) {
	extra := map[string]any{"thinking": map[string]any{"type": "enabled"}, "model": "overridden", "stream": false}
	var got map[string]any
	client := newTidyUpstream(t, extra, func(b map[string]any) { got = b }, func(w http.ResponseWriter) {
		sse(w, "stop", "## 出行\n\n", "- 周六出发")
	})
	ts := newTestWith(t, Options{Tidy: client})

	code, lines := tidyLines(t, ts, `{"text":"那个周六出发吧","headings":["# 计划","## 周末"],"prefix":"- "}`)
	if code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	want := []tidyMessage{{Type: "delta", Text: "## 出行\n\n"}, {Type: "delta", Text: "- 周六出发"}, {Type: "done"}}
	if fmt.Sprint(lines) != fmt.Sprint(want) {
		t.Fatalf("lines = %+v", lines)
	}

	// Standard fields win over extra_body; vendor fields pass through.
	if got["model"] != "test-model" || got["stream"] != true {
		t.Errorf("model = %v, stream = %v", got["model"], got["stream"])
	}
	if th, _ := got["thinking"].(map[string]any); th["type"] != "enabled" {
		t.Errorf("thinking = %v", got["thinking"])
	}
	msgs, _ := got["messages"].([]any)
	if len(msgs) != 2 {
		t.Fatalf("messages = %v", got["messages"])
	}
	user, _ := msgs[1].(map[string]any)["content"].(string)
	for _, part := range []string{"<上文标题>\n# 计划\n## 周末\n</上文标题>", "<行首>- </行首>", "<口语稿>\n那个周六出发吧\n</口语稿>"} {
		if !strings.Contains(user, part) {
			t.Errorf("user message lacks %q:\n%s", part, user)
		}
	}
	if strings.Contains(user, "<行尾>") {
		t.Errorf("user message has an empty suffix:\n%s", user)
	}
}

func TestTidyFailures(t *testing.T) {
	for _, tc := range []struct {
		name   string
		handle func(w http.ResponseWriter)
		want   string
	}{
		{"bad key", func(w http.ResponseWriter) { http.Error(w, `{"error":"auth"}`, http.StatusUnauthorized) }, "整理服务的 API Key 无效"},
		{"busy", func(w http.ResponseWriter) { http.Error(w, "slow down", http.StatusTooManyRequests) }, "整理服务繁忙，请稍后再试"},
		{"truncated", func(w http.ResponseWriter) { sse(w, "length", "一半") }, "整理结果不完整，没有替换"},
		// The connection drops before the model finishes: not a success.
		{"cut off", func(w http.ResponseWriter) { sse(w, "", "一半") }, "整理失败，请稍后再试"},
		{"empty", func(w http.ResponseWriter) { sse(w, "stop") }, "整理失败，请稍后再试"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ts := newTestWith(t, Options{Tidy: newTidyUpstream(t, nil, nil, tc.handle)})
			_, lines := tidyLines(t, ts, `{"text":"随便说点什么"}`)
			if len(lines) == 0 {
				t.Fatal("no response lines")
			}
			last := lines[len(lines)-1]
			if last.Type != "error" || last.Message != tc.want {
				t.Fatalf("last = %+v, want error %q", last, tc.want)
			}
		})
	}
}

func TestTidyRejectsBadRequests(t *testing.T) {
	if rec := do(t, newTest(t), "POST", "/api/tidy", `{"text":"说点什么"}`); rec.Code != http.StatusServiceUnavailable ||
		!strings.Contains(rec.Body.String(), "整理未配置") {
		t.Errorf("unconfigured: %d %s", rec.Code, rec.Body)
	}
	called := false
	ts := newTestWith(t, Options{Tidy: newTidyUpstream(t, nil, func(map[string]any) { called = true }, func(w http.ResponseWriter) {})})
	for _, body := range []string{`{"text":""}`, `{"text":"` + strings.Repeat("长", maxTidyText+1) + `"}`, `not json`} {
		if rec := do(t, ts, "POST", "/api/tidy", body); rec.Code != http.StatusBadRequest {
			t.Errorf("%.20s: status %d", body, rec.Code)
		}
	}
	if called {
		t.Error("a bad request reached the model")
	}
}
