package server

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"asr/internal/dashscope"
)

/*
 * GET /api/asr upgrades to a WebSocket that relays one recognition:
 *
 *   client → server  binary frames of 16 kHz mono PCM16, then the text
 *                    message {"type":"finish"} once the user lets go
 *   server → client  {"type":"partial"|"final","sentence":n,"text":"..."}
 *                    as results arrive, then {"type":"done"}, or
 *                    {"type":"error","message":"..."} if it failed
 *
 * The server closes the socket after done or error. Audio may be sent
 * before the upstream task has started; it waits in the socket.
 */

const (
	// MaxRecording is how long one recognition may listen. The client stops
	// at this mark; the server allows a little more audio for buffering.
	MaxRecording = 5 * time.Minute
	maxAudio     = int((MaxRecording + 10*time.Second) / time.Second * dashscope.SampleRate * 2)
	// The client sends audio about every 100 ms while recording.
	idleTimeout = 10 * time.Second
)

// finishTimeout bounds the wait for the final results after finish; they
// usually take well under a second. Tests shorten it.
var finishTimeout = 10 * time.Second

var (
	errClientGone    = errors.New("client left")
	errIdle          = errors.New("no audio from the client")
	errTooLong       = errors.New("recording too long")
	errFinishTimeout = errors.New("no final result in time")
	errSessionLimit  = errors.New("session too long")
)

type asrMessage struct {
	Type     string `json:"type"`
	Sentence int    `json:"sentence,omitempty"`
	Text     string `json:"text,omitempty"`
	Message  string `json:"message,omitempty"`
}

func (s *server) recognize(w http.ResponseWriter, r *http.Request) {
	// Accept rejects cross-origin handshakes, which the session cookie
	// would otherwise authenticate.
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(64 << 10)

	base, stop := context.WithTimeoutCause(r.Context(), MaxRecording+time.Minute, errSessionLimit)
	defer stop()
	ctx, cancel := context.WithCancelCause(base)
	defer cancel(nil)

	fail := func(msg string) {
		// ctx may be done already; the goodbye gets its own deadline.
		wctx, done := context.WithTimeout(context.WithoutCancel(r.Context()), 5*time.Second)
		defer done()
		if writeASR(wctx, conn, asrMessage{Type: "error", Message: msg}) == nil {
			conn.Close(websocket.StatusNormalClosure, "")
		}
	}

	if s.opts.ASR == nil {
		fail("语音识别未配置")
		return
	}
	task, err := s.opts.ASR.Start(ctx)
	if err != nil {
		slog.Error("start asr task", "err", err)
		fail("连不上语音识别服务")
		return
	}
	defer task.Close()
	// Reading with ctx would close the socket when ctx ends, before the
	// error can be sent; the deferred CloseNow ends this read instead.
	go pumpAudio(context.WithoutCancel(ctx), ctx, cancel, conn, task)

	for {
		res, err := task.Next(ctx)
		if errors.Is(err, dashscope.ErrFinished) {
			if writeASR(ctx, conn, asrMessage{Type: "done"}) == nil {
				conn.Close(websocket.StatusNormalClosure, "")
			}
			return
		}
		if err != nil {
			switch cause := context.Cause(ctx); {
			case errors.Is(cause, errClientGone):
			case errors.Is(cause, errTooLong):
				fail("录音超过 5 分钟")
			case errors.Is(cause, errIdle):
				fail("录音中断")
			case errors.Is(cause, errFinishTimeout), errors.Is(cause, errSessionLimit):
				fail("语音识别超时")
			default:
				slog.Error("asr task", "err", err, "cause", cause)
				fail("语音识别失败")
			}
			return
		}
		typ := "partial"
		if res.Final {
			typ = "final"
		}
		if writeASR(ctx, conn, asrMessage{Type: typ, Sentence: res.Sentence, Text: res.Text}) != nil {
			return
		}
	}
}

// pumpAudio forwards the client's audio until it finishes, then waits for
// the client to go away. Whatever goes wrong cancels ctx with the cause.
// It reads the client with readCtx, which outlives ctx.
func pumpAudio(readCtx, ctx context.Context, cancel context.CancelCauseFunc, conn *websocket.Conn, task *dashscope.Task) {
	idle := time.AfterFunc(idleTimeout, func() { cancel(errIdle) })
	defer idle.Stop()
	finished := false
	size := 0
	for {
		typ, data, err := conn.Read(readCtx)
		if err != nil {
			cancel(errClientGone)
			return
		}
		if finished {
			continue
		}
		idle.Reset(idleTimeout)
		if typ == websocket.MessageBinary {
			if size += len(data); size > maxAudio {
				cancel(errTooLong)
				return
			}
			if err := task.Send(ctx, data); err != nil {
				cancel(err)
				return
			}
			continue
		}
		var m asrMessage
		if json.Unmarshal(data, &m) != nil || m.Type != "finish" {
			continue
		}
		finished = true
		idle.Stop()
		if err := task.Finish(ctx); err != nil {
			cancel(err)
			return
		}
		t := time.AfterFunc(finishTimeout, func() { cancel(errFinishTimeout) })
		defer t.Stop()
	}
}

func writeASR(ctx context.Context, conn *websocket.Conn, m asrMessage) error {
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, data)
}
