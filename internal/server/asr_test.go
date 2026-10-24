package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"asr/internal/dashscope"
)

// fakeUpstream plays DashScope: it checks the run-task, collects the audio
// and, on finish-task, runs script to answer.
type fakeUpstream struct {
	t      *testing.T
	script func(ctx context.Context, send func(event string, payload map[string]any))
	audio  chan []byte
}

func newUpstream(t *testing.T, script func(ctx context.Context, send func(string, map[string]any))) *dashscope.Client {
	f := &fakeUpstream{t: t, script: script, audio: make(chan []byte, 1)}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	return dashscope.NewURL("ws"+strings.TrimPrefix(srv.URL, "http"), "test-model", "test-key")
}

func (f *fakeUpstream) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer test-key" {
		f.t.Errorf("Authorization = %q", r.Header.Get("Authorization"))
	}
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	ctx := r.Context()
	send := func(event string, payload map[string]any) {
		data, _ := json.Marshal(map[string]any{"header": map[string]any{"event": event}, "payload": payload})
		conn.Write(ctx, websocket.MessageText, data)
	}
	var audio bytes.Buffer
	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if typ == websocket.MessageBinary {
			audio.Write(data)
			continue
		}
		var m struct {
			Header  struct{ Action string } `json:"header"`
			Payload struct {
				Model      string         `json:"model"`
				Parameters map[string]any `json:"parameters"`
			} `json:"payload"`
		}
		json.Unmarshal(data, &m)
		switch m.Header.Action {
		case "run-task":
			if m.Payload.Model != "test-model" || m.Payload.Parameters["intermediate_result_enabled"] != true {
				f.t.Errorf("run-task payload = %+v", m.Payload)
			}
			send("task-started", map[string]any{})
		case "finish-task":
			f.audio <- audio.Bytes()
			f.script(ctx, send)
		}
	}
}

func sentence(id int, text string, end bool) map[string]any {
	return map[string]any{"output": map[string]any{"sentence": map[string]any{"sentence_id": id, "text": text, "sentence_end": end}}}
}

// dialASR opens /api/asr on ts as the signed-in user.
func dialASR(t *testing.T, ts *testServer, origin string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	srv := httptest.NewServer(ts)
	t.Cleanup(srv.Close)
	h := http.Header{"Cookie": {"asr_session=" + ts.session}}
	if origin != "" {
		h.Set("Origin", origin)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/api/asr", &websocket.DialOptions{HTTPHeader: h})
}

// readAll returns the messages until the server closes the socket.
func readAll(t *testing.T, conn *websocket.Conn) []asrMessage {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var got []asrMessage
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			if websocket.CloseStatus(err) != websocket.StatusNormalClosure {
				t.Errorf("read: %v", err)
			}
			return got
		}
		var m asrMessage
		json.Unmarshal(data, &m)
		got = append(got, m)
	}
}

func finish(t *testing.T, conn *websocket.Conn, audio ...[]byte) {
	t.Helper()
	ctx := context.Background()
	for _, a := range audio {
		if err := conn.Write(ctx, websocket.MessageBinary, a); err != nil {
			t.Fatal(err)
		}
	}
	if err := conn.Write(ctx, websocket.MessageText, []byte(`{"type":"finish"}`)); err != nil {
		t.Fatal(err)
	}
}

func TestASRRelaysResults(t *testing.T) {
	client := newUpstream(t, func(ctx context.Context, send func(string, map[string]any)) {
		send("result-generated", sentence(1, "今天", false))
		send("result-generated", map[string]any{"output": map[string]any{"sentence": map[string]any{"heartbeat": true}}})
		send("result-generated", sentence(1, "今天天气不错。", true))
		send("result-generated", sentence(2, "", true))
		send("task-finished", map[string]any{"usage": map[string]any{"duration": 2}})
	})
	ts := newTestWith(t, Options{ASR: client})
	conn, _, err := dialASR(t, ts, "")
	if err != nil {
		t.Fatal(err)
	}
	finish(t, conn, []byte{1, 2}, []byte{3, 4})

	got := readAll(t, conn)
	want := []asrMessage{
		{Type: "partial", Sentence: 1, Text: "今天"},
		{Type: "final", Sentence: 1, Text: "今天天气不错。"},
		{Type: "final", Sentence: 2},
		{Type: "done"},
	}
	if len(got) != len(want) {
		t.Fatalf("messages = %+v, want %+v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("message %d = %+v, want %+v", i, got[i], want[i])
		}
	}
}

func TestASRForwardsAudio(t *testing.T) {
	audio := make(chan []byte, 1)
	srvUp := &fakeUpstream{t: t, audio: audio, script: func(ctx context.Context, send func(string, map[string]any)) {
		send("task-finished", map[string]any{})
	}}
	up := httptest.NewServer(srvUp)
	defer up.Close()
	ts := newTestWith(t, Options{ASR: dashscope.NewURL("ws"+strings.TrimPrefix(up.URL, "http"), "test-model", "test-key")})
	conn, _, err := dialASR(t, ts, "")
	if err != nil {
		t.Fatal(err)
	}
	finish(t, conn, []byte{1, 2}, []byte{3, 4})
	if got := <-audio; !bytes.Equal(got, []byte{1, 2, 3, 4}) {
		t.Errorf("upstream audio = %v", got)
	}
	if got := readAll(t, conn); len(got) != 1 || got[0].Type != "done" {
		t.Errorf("messages = %+v", got)
	}
}

func TestASRFailures(t *testing.T) {
	old := finishTimeout
	finishTimeout = 200 * time.Millisecond
	t.Cleanup(func() { finishTimeout = old })

	for _, tc := range []struct {
		name   string
		script func(ctx context.Context, send func(string, map[string]any))
		want   string
	}{
		{"task failed", func(ctx context.Context, send func(string, map[string]any)) {
			send("result-generated", sentence(1, "今天", true))
			send("task-failed", map[string]any{})
		}, "语音识别失败"},
		{"no final result", func(ctx context.Context, send func(string, map[string]any)) {
			send("result-generated", sentence(1, "今天", false))
		}, "语音识别超时"},
		{"upstream drops", func(ctx context.Context, send func(string, map[string]any)) {
			panic(http.ErrAbortHandler)
		}, "语音识别失败"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ts := newTestWith(t, Options{ASR: newUpstream(t, tc.script)})
			conn, _, err := dialASR(t, ts, "")
			if err != nil {
				t.Fatal(err)
			}
			finish(t, conn, []byte{1, 2})
			got := readAll(t, conn)
			last := got[len(got)-1]
			if last.Type != "error" || last.Message != tc.want {
				t.Errorf("messages = %+v, want error %q", got, tc.want)
			}
			for _, m := range got {
				if m.Type == "done" {
					t.Errorf("a failed task reported done: %+v", got)
				}
			}
		})
	}
}

func TestASRNotConfigured(t *testing.T) {
	ts := newTest(t)
	conn, _, err := dialASR(t, ts, "")
	if err != nil {
		t.Fatal(err)
	}
	if got := readAll(t, conn); len(got) != 1 || got[0].Message != "语音识别未配置" {
		t.Errorf("messages = %+v", got)
	}
}

func TestASRRejectsOtherOriginsAndStrangers(t *testing.T) {
	ts := newTest(t)
	if _, resp, err := dialASR(t, ts, "https://evil.test"); err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Errorf("cross-origin handshake: err = %v, resp = %v", err, resp)
	}
	ts.session = "nope"
	if _, resp, err := dialASR(t, ts, ""); err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("handshake without a session: err = %v, resp = %v", err, resp)
	}
}
