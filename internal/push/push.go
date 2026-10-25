// Package push keeps the long-lived connections of the Mac clients and
// delivers dictated text to them.
//
// Connections live in memory only; a Mac that is not connected is
// offline, and nothing is queued for it. The text passes through and is
// never stored or logged.
package push

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/coder/websocket"

	"asr/internal/store"
)

/*
 * One WebSocket per Mac carries JSON text messages:
 *
 *   client → server  {"type":"activity"}  the user typed or moved the
 *                    mouse; sent at most every few seconds while active
 *                    {"type":"ack","id":"…","error":"…"}  the insert with
 *                    this id is done; error is set, in Chinese, if the
 *                    Mac could not insert it
 *   server → client  {"type":"insert","id":"…","text":"…"}
 *
 * The server pings every PingInterval; a connection that does not answer
 * within PingTimeout is dropped. A second connection for the same device
 * replaces the first.
 */

var (
	// ErrNoTarget means none of the user's Macs is connected.
	ErrNoTarget = errors.New("no Mac online")
	// ErrOffline means the requested Mac is not connected.
	ErrOffline = errors.New("Mac offline")
	// ErrTimeout means the Mac did not acknowledge in time.
	ErrTimeout = errors.New("no ack from the Mac")
	// ErrGone means the connection dropped before the Mac acknowledged.
	ErrGone = errors.New("Mac disconnected")
)

// DeviceError is the reason a Mac gave for not inserting the text.
type DeviceError struct{ Message string }

func (e *DeviceError) Error() string { return "Mac refused: " + e.Message }

// maxReason caps the reason a Mac reports, in bytes.
const maxReason = 300

// Hub is safe for concurrent use.
type Hub struct {
	PingInterval time.Duration
	PingTimeout  time.Duration
	AckTimeout   time.Duration

	mu    sync.Mutex
	users map[int64]map[int64]*client // user → device → connection
}

// NewHub returns a hub with the production timings.
func NewHub() *Hub {
	return &Hub{
		PingInterval: 20 * time.Second,
		PingTimeout:  10 * time.Second,
		AckTimeout:   5 * time.Second,
		users:        map[int64]map[int64]*client{},
	}
}

type client struct {
	conn        *websocket.Conn
	device      store.Device
	connectedAt time.Time
	lastActive  time.Time // zero until the first report
	acks        map[string]chan string
	gone        chan struct{}
}

// Status is what the hub knows about a device right now.
type Status struct {
	Online     bool
	LastActive time.Time // zero if it has not reported activity
}

type message struct {
	Type  string `json:"type"`
	ID    string `json:"id,omitempty"`
	Text  string `json:"text,omitempty"`
	Error string `json:"error,omitempty"`
}

// Serve runs the connection of a device until it closes or ctx ends.
func (h *Hub) Serve(ctx context.Context, conn *websocket.Conn, d store.Device) {
	conn.SetReadLimit(4 << 10)
	c := &client{conn: conn, device: d, connectedAt: time.Now(), acks: map[string]chan string{}, gone: make(chan struct{})}
	h.mu.Lock()
	devices := h.users[d.UserID]
	if devices == nil {
		devices = map[int64]*client{}
		h.users[d.UserID] = devices
	}
	if old := devices[d.ID]; old != nil {
		go old.conn.Close(websocket.StatusPolicyViolation, "replaced by a new connection")
	}
	devices[d.ID] = c
	h.mu.Unlock()
	slog.Info("device connected", "user", d.UserID, "device", d.ID)

	ctx, cancel := context.WithCancel(ctx)
	defer func() {
		cancel()
		conn.CloseNow()
		h.mu.Lock()
		if h.users[d.UserID][d.ID] == c {
			delete(h.users[d.UserID], d.ID)
			if len(h.users[d.UserID]) == 0 {
				delete(h.users, d.UserID)
			}
		}
		close(c.gone)
		h.mu.Unlock()
		slog.Info("device disconnected", "user", d.UserID, "device", d.ID)
	}()

	go h.keepAlive(ctx, conn)
	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		var m message
		if typ != websocket.MessageText || json.Unmarshal(data, &m) != nil {
			continue
		}
		switch m.Type {
		case "activity":
			h.mu.Lock()
			c.lastActive = time.Now()
			h.mu.Unlock()
		case "ack":
			h.mu.Lock()
			if ch := c.acks[m.ID]; ch != nil {
				delete(c.acks, m.ID)
				if len(m.Error) > maxReason {
					m.Error = m.Error[:maxReason]
				}
				ch <- m.Error
			}
			h.mu.Unlock()
		}
	}
}

// keepAlive drops the connection once a ping goes unanswered.
func (h *Hub) keepAlive(ctx context.Context, conn *websocket.Conn) {
	t := time.NewTicker(h.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		pctx, cancel := context.WithTimeout(ctx, h.PingTimeout)
		err := conn.Ping(pctx)
		cancel()
		if err != nil {
			conn.CloseNow()
			return
		}
	}
}

// Statuses reports the user's connected devices.
func (h *Hub) Statuses(userID int64) map[int64]Status {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := map[int64]Status{}
	for id, c := range h.users[userID] {
		out[id] = Status{Online: true, LastActive: c.lastActive}
	}
	return out
}

// Disconnect closes the device's connection, if any, e.g. once it is
// revoked.
func (h *Hub) Disconnect(userID, deviceID int64) {
	h.mu.Lock()
	c := h.users[userID][deviceID]
	h.mu.Unlock()
	if c != nil {
		go c.conn.Close(websocket.StatusPolicyViolation, "device revoked")
	}
}

// Send inserts text at the cursor of one of the user's Macs and waits for
// it to acknowledge. deviceID 0 picks the Mac used most recently. It
// returns the device that took the text.
func (h *Hub) Send(ctx context.Context, userID, deviceID int64, text string) (store.Device, error) {
	h.mu.Lock()
	c := h.target(userID, deviceID)
	if c == nil {
		h.mu.Unlock()
		if deviceID != 0 {
			return store.Device{}, ErrOffline
		}
		return store.Device{}, ErrNoTarget
	}
	id := newID()
	ack := make(chan string, 1)
	c.acks[id] = ack
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(c.acks, id)
		h.mu.Unlock()
	}()

	timer := time.NewTimer(h.AckTimeout)
	defer timer.Stop()
	// A failed or cancelled write closes the connection, so it does not
	// follow the caller's context.
	wctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), h.AckTimeout)
	defer cancel()
	data, _ := json.Marshal(message{Type: "insert", ID: id, Text: text})
	if err := c.conn.Write(wctx, websocket.MessageText, data); err != nil {
		return store.Device{}, ErrGone
	}
	select {
	case reason := <-ack:
		if reason != "" {
			return store.Device{}, &DeviceError{Message: reason}
		}
		return c.device, nil
	case <-timer.C:
		return store.Device{}, ErrTimeout
	case <-c.gone:
		return store.Device{}, ErrGone
	case <-ctx.Done():
		return store.Device{}, ctx.Err()
	}
}

// target picks the connection to send to; h.mu must be held.
func (h *Hub) target(userID, deviceID int64) *client {
	devices := h.users[userID]
	if deviceID != 0 {
		return devices[deviceID]
	}
	var best *client
	for _, c := range devices {
		if best == nil || c.lastActive.After(best.lastActive) ||
			c.lastActive.Equal(best.lastActive) && c.connectedAt.After(best.connectedAt) {
			best = c
		}
	}
	return best
}

func newID() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}
