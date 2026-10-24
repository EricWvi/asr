// Package dashscope streams audio to the real-time speech recognition of
// Alibaba Cloud Model Studio (DashScope) over its duplex WebSocket API:
// run-task, binary audio frames, finish-task.
package dashscope

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/coder/websocket"
)

// SampleRate is the rate of the 16-bit mono PCM audio a task takes.
const SampleRate = 16000

// Client starts recognition tasks.
type Client struct {
	url, model, apiKey string
}

// New returns a client for the workspace host, which already names the
// workspace and region, e.g. "llm-xxx.cn-beijing.maas.aliyuncs.com".
func New(host, model, apiKey string) *Client {
	return NewURL("wss://"+host+"/api-ws/v1/inference", model, apiKey)
}

// NewURL returns a client for the inference endpoint at url.
func NewURL(url, model, apiKey string) *Client {
	return &Client{url: url, model: model, apiKey: apiKey}
}

// Result is the text of one sentence so far. Interim results repeat the
// whole sentence and replace the previous one with the same ID; Final
// marks its last result.
type Result struct {
	Sentence int
	Text     string
	Final    bool
}

// Task is one recognition: send audio, then Finish, and read results with
// Next until it returns ErrFinished.
type Task struct {
	conn *websocket.Conn
	id   string
}

// Start connects and waits until the service accepts the task. The task
// lives no longer than ctx.
func (c *Client) Start(ctx context.Context) (*Task, error) {
	conn, _, err := websocket.Dial(ctx, c.url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + c.apiKey}},
	})
	if err != nil {
		return nil, fmt.Errorf("connect: %w", err)
	}
	conn.SetReadLimit(1 << 20)
	t := &Task{conn: conn, id: newID()}
	err = t.command(ctx, "run-task", map[string]any{
		"task_group": "audio",
		"task":       "asr",
		"function":   "recognition",
		"model":      c.model,
		"parameters": map[string]any{
			"format":                      "pcm",
			"sample_rate":                 SampleRate,
			"intermediate_result_enabled": true,
			"disfluency_removal_enabled":  true,
			"language_hints":              []string{"zh"},
		},
		"input": map[string]any{},
	})
	if err == nil {
		var m message
		if m, err = t.read(ctx); err == nil && m.Header.Event != "task-started" {
			err = fmt.Errorf("unexpected %s before task-started", m.Header.Event)
		}
	}
	if err != nil {
		t.Close()
		return nil, err
	}
	return t, nil
}

// Send streams a chunk of 16 kHz mono PCM16 audio.
func (t *Task) Send(ctx context.Context, pcm []byte) error {
	return t.conn.Write(ctx, websocket.MessageBinary, pcm)
}

// Finish tells the service the audio has ended; the final results follow.
func (t *Task) Finish(ctx context.Context) error {
	return t.command(ctx, "finish-task", map[string]any{"input": map[string]any{}})
}

// ErrFinished is returned by Next once the task has finished normally.
// A dropped connection is a different error, never this one.
var ErrFinished = errors.New("recognition finished")

// Next returns the next result. It returns ErrFinished once the task has
// finished, and another error if the task failed or the connection broke.
func (t *Task) Next(ctx context.Context) (Result, error) {
	for {
		m, err := t.read(ctx)
		if err != nil {
			return Result{}, err
		}
		switch m.Header.Event {
		case "result-generated":
			s := m.Payload.Output.Sentence
			if s.Heartbeat {
				continue
			}
			return Result{Sentence: s.SentenceID, Text: s.Text, Final: s.SentenceEnd}, nil
		case "task-finished":
			slog.Info("asr task finished", "task", t.id, "billed_seconds", m.Payload.Usage.Duration)
			return Result{}, ErrFinished
		default:
			return Result{}, fmt.Errorf("unexpected event %q", m.Header.Event)
		}
	}
}

// Close drops the connection; an unfinished task is abandoned.
func (t *Task) Close() {
	t.conn.CloseNow()
}

type message struct {
	Header struct {
		Event        string `json:"event"`
		ErrorCode    string `json:"error_code"`
		ErrorMessage string `json:"error_message"`
	} `json:"header"`
	Payload struct {
		Output struct {
			Sentence struct {
				SentenceID  int    `json:"sentence_id"`
				Text        string `json:"text"`
				SentenceEnd bool   `json:"sentence_end"`
				Heartbeat   bool   `json:"heartbeat"`
			} `json:"sentence"`
		} `json:"output"`
		Usage struct {
			Duration float64 `json:"duration"`
		} `json:"usage"`
	} `json:"payload"`
}

// ErrFailed is wrapped by the error of a task the service reports failed.
var ErrFailed = errors.New("recognition failed")

func (t *Task) read(ctx context.Context) (message, error) {
	var m message
	typ, data, err := t.conn.Read(ctx)
	if err != nil {
		return m, err
	}
	if typ != websocket.MessageText {
		return m, errors.New("unexpected binary message")
	}
	if err := json.Unmarshal(data, &m); err != nil {
		return m, fmt.Errorf("decode event: %w", err)
	}
	if m.Header.Event == "task-failed" {
		return m, fmt.Errorf("%w: %s %s", ErrFailed, m.Header.ErrorCode, m.Header.ErrorMessage)
	}
	return m, nil
}

func (t *Task) command(ctx context.Context, action string, payload map[string]any) error {
	data, err := json.Marshal(map[string]any{
		"header":  map[string]any{"action": action, "task_id": t.id, "streaming": "duplex"},
		"payload": payload,
	})
	if err != nil {
		return err
	}
	return t.conn.Write(ctx, websocket.MessageText, data)
}

func newID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}
