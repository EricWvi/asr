package server

import (
	"errors"
	"net/http"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/coder/websocket"

	"asr/internal/auth"
	"asr/internal/push"
	"asr/internal/store"
)

// maxSendText caps one send to a Mac, in characters.
const maxSendText = 20000

type deviceView struct {
	store.Device
	// Online is true while a Mac keeps its push connection open.
	Online bool `json:"online"`
	// LastActiveAt is when the user last typed or moved the mouse on an
	// online Mac, in Unix milliseconds.
	LastActiveAt int64 `json:"last_active_at,omitempty"`
}

func (s *server) listDevices(w http.ResponseWriter, r *http.Request) {
	user := auth.User(r.Context())
	devices, err := s.store.ListDevices(r.Context(), user.ID)
	if err != nil {
		writeStoreError(w, r, err)
		return
	}
	statuses := s.hub.Statuses(user.ID)
	out := make([]deviceView, len(devices))
	for i, d := range devices {
		st := statuses[d.ID]
		out[i] = deviceView{Device: d, Online: st.Online}
		if !st.LastActive.IsZero() {
			out[i].LastActiveAt = st.LastActive.UnixMilli()
		}
	}
	// Online Macs first; the rest stay most recently used first.
	sort.SliceStable(out, func(i, j int) bool { return out[i].Online && !out[j].Online })
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, out)
}

// revokeDevice signs a device out. A device may revoke itself, which is
// how a client signs out; other devices are revoked from the web.
func (s *server) revokeDevice(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r)
	if !ok {
		return
	}
	if d, isDevice := auth.Device(r.Context()); isDevice && d.ID != id {
		writeError(w, http.StatusForbidden, "只能在网页上吊销其他设备")
		return
	}
	user := auth.User(r.Context())
	if err := s.store.DeleteDevice(r.Context(), user.ID, id); err != nil {
		writeStoreError(w, r, err)
		return
	}
	s.hub.Disconnect(user.ID, id)
	w.WriteHeader(http.StatusNoContent)
}

// streamDevice is the push connection of a Mac; see package push.
func (s *server) streamDevice(w http.ResponseWriter, r *http.Request) {
	d, ok := auth.Device(r.Context())
	if !ok || d.Platform != store.PlatformMac {
		writeError(w, http.StatusForbidden, "只有 Mac 客户端可以连接")
		return
	}
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	s.hub.Serve(r.Context(), conn, d)
}

// send inserts dictated text at the cursor on a Mac. Nothing is queued:
// without an online Mac it fails at once.
func (s *server) send(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Text     string `json:"text"`
		DeviceID int64  `json:"device_id"`
	}
	if !decode(w, r, &body, defaultBody) {
		return
	}
	if strings.TrimSpace(body.Text) == "" {
		writeError(w, http.StatusBadRequest, "没有要发送的文字")
		return
	}
	if utf8.RuneCountInString(body.Text) > maxSendText {
		writeError(w, http.StatusBadRequest, "文字太长，一次最多 20000 字")
		return
	}
	d, err := s.hub.Send(r.Context(), auth.User(r.Context()).ID, body.DeviceID, body.Text)
	var refused *push.DeviceError
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, map[string]any{"device": map[string]any{"id": d.ID, "name": d.Name}})
	case errors.Is(err, push.ErrNoTarget):
		writeError(w, http.StatusConflict, "没有在线的电脑")
	case errors.Is(err, push.ErrOffline):
		writeError(w, http.StatusConflict, "这台电脑不在线")
	case errors.Is(err, push.ErrTimeout):
		writeError(w, http.StatusGatewayTimeout, "电脑没有响应")
	case errors.Is(err, push.ErrGone):
		writeError(w, http.StatusBadGateway, "电脑的连接断开了")
	case errors.As(err, &refused):
		writeError(w, http.StatusBadGateway, refused.Message)
	default:
		// The caller went away; nobody reads this.
		writeError(w, http.StatusServiceUnavailable, "发送已取消")
	}
}
