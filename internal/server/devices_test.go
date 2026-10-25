package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"asr/internal/push"
	"asr/internal/store"
)

// deviceTest serves the API on a real listener so Macs can connect.
type deviceTest struct {
	*testServer
	url  string
	user int64
}

func newDeviceTest(t *testing.T) *deviceTest {
	t.Helper()
	hub := push.NewHub()
	hub.PingInterval, hub.PingTimeout, hub.AckTimeout = 50*time.Millisecond, 50*time.Millisecond, 300*time.Millisecond
	ts := newTestWith(t, Options{Version: "test", Hub: hub})
	srv := httptest.NewServer(ts)
	t.Cleanup(srv.Close)
	u, err := ts.store.UpsertUser(context.Background(), store.Identity{Issuer: "https://idp.test", Subject: "alice", Name: "alice"})
	if err != nil {
		t.Fatal(err)
	}
	return &deviceTest{testServer: ts, url: srv.URL, user: u.ID}
}

func (dt *deviceTest) device(t *testing.T, name, platform string) (store.Device, string) {
	t.Helper()
	d, token, err := dt.store.CreateDevice(context.Background(), dt.user, name, platform)
	if err != nil {
		t.Fatal(err)
	}
	return d, token
}

// call sends a request authenticated by a device token, or by the
// browser session when token is empty.
func (dt *deviceTest) call(t *testing.T, method, path, token, body string) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	} else {
		req.AddCookie(&http.Cookie{Name: "asr_session", Value: dt.session})
	}
	rec := httptest.NewRecorder()
	dt.ServeHTTP(rec, req)
	var out map[string]any
	json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func (dt *deviceTest) list(t *testing.T) []map[string]any {
	t.Helper()
	req := httptest.NewRequest("GET", "/api/devices", nil)
	req.AddCookie(&http.Cookie{Name: "asr_session", Value: dt.session})
	rec := httptest.NewRecorder()
	dt.ServeHTTP(rec, req)
	var out []map[string]any
	json.Unmarshal(rec.Body.Bytes(), &out)
	return out
}

// fakeMac connects like the Mac client and answers inserts with reply.
type fakeMac struct {
	conn  *websocket.Conn
	texts chan string
	reply func(text string) string // returns the error to report, or ""
	mute  bool                     // never acknowledge
}

func (dt *deviceTest) connect(t *testing.T, token string, reply func(string) string) *fakeMac {
	t.Helper()
	ctx := context.Background()
	conn, res, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(dt.url, "http")+"/api/devices/stream",
		&websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err != nil {
		code := 0
		if res != nil {
			code = res.StatusCode
		}
		t.Fatalf("dial: %v (status %d)", err, code)
	}
	t.Cleanup(func() { conn.CloseNow() })
	m := &fakeMac{conn: conn, texts: make(chan string, 8), reply: reply}
	go func() {
		for {
			_, data, err := conn.Read(ctx)
			if err != nil {
				close(m.texts)
				return
			}
			var msg struct{ Type, ID, Text string }
			json.Unmarshal(data, &msg)
			m.texts <- msg.Text
			if m.reply == nil {
				continue
			}
			ack, _ := json.Marshal(map[string]string{"type": "ack", "id": msg.ID, "error": m.reply(msg.Text)})
			conn.Write(ctx, websocket.MessageText, ack)
		}
	}()
	return m
}

func (m *fakeMac) activity(t *testing.T) {
	t.Helper()
	if err := m.conn.Write(context.Background(), websocket.MessageText, []byte(`{"type":"activity"}`)); err != nil {
		t.Fatal(err)
	}
}

// waitFor polls cond, since the hub learns about connections and
// activity asynchronously.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatalf("timed out waiting for %s", what)
}

func online(dt *deviceTest, t *testing.T, id int64) bool {
	for _, d := range dt.list(t) {
		if int64(d["id"].(float64)) == id {
			return d["online"] == true
		}
	}
	return false
}

func ok(string) string { return "" }

func TestSendGoesToMostRecentlyActiveMac(t *testing.T) {
	dt := newDeviceTest(t)
	_, phone := dt.device(t, "Pixel", store.PlatformAndroid)

	if code, out := dt.call(t, "POST", "/api/send", phone, `{"text":"你好"}`); code != http.StatusConflict || out["error"] != "没有在线的电脑" {
		t.Fatalf("no Mac: %d %v", code, out)
	}

	air, airToken := dt.device(t, "Air", store.PlatformMac)
	mini, miniToken := dt.device(t, "Mini", store.PlatformMac)
	airConn := dt.connect(t, airToken, ok)
	miniConn := dt.connect(t, miniToken, ok)
	waitFor(t, "both online", func() bool { return online(dt, t, air.ID) && online(dt, t, mini.ID) })

	airConn.activity(t)
	waitFor(t, "air active", func() bool {
		for _, d := range dt.list(t) {
			if d["name"] == "Air" && d["last_active_at"] != nil {
				return true
			}
		}
		return false
	})
	code, out := dt.call(t, "POST", "/api/send", phone, `{"text":"第一句"}`)
	if code != http.StatusOK || out["device"].(map[string]any)["name"] != "Air" {
		t.Fatalf("send: %d %v", code, out)
	}
	if got := <-airConn.texts; got != "第一句" {
		t.Fatalf("air got %q", got)
	}

	// An explicit target wins over activity.
	code, out = dt.call(t, "POST", "/api/send", phone, `{"text":"第二句","device_id":`+itoa(mini.ID)+`}`)
	if code != http.StatusOK || out["device"].(map[string]any)["name"] != "Mini" || <-miniConn.texts != "第二句" {
		t.Fatalf("send to mini: %d %v", code, out)
	}

	// The Mac's own failure comes back to the sender.
	miniConn.reply = func(string) string { return "没有辅助功能权限" }
	code, out = dt.call(t, "POST", "/api/send", phone, `{"text":"x","device_id":`+itoa(mini.ID)+`}`)
	if code != http.StatusBadGateway || out["error"] != "没有辅助功能权限" {
		t.Fatalf("refused: %d %v", code, out)
	}

	// A Mac that never answers times out.
	airConn.reply = nil
	code, _ = dt.call(t, "POST", "/api/send", phone, `{"text":"x","device_id":`+itoa(air.ID)+`}`)
	if code != http.StatusGatewayTimeout {
		t.Fatalf("silent Mac: %d", code)
	}

	// The browser can send too; empty text is rejected.
	if code, _ := dt.call(t, "POST", "/api/send", "", `{"text":"  "}`); code != http.StatusBadRequest {
		t.Fatalf("empty text: %d", code)
	}
}

func TestSendToOfflineMacFails(t *testing.T) {
	dt := newDeviceTest(t)
	mac, macToken := dt.device(t, "Air", store.PlatformMac)
	m := dt.connect(t, macToken, ok)
	waitFor(t, "online", func() bool { return online(dt, t, mac.ID) })
	m.conn.CloseNow()
	waitFor(t, "offline", func() bool { return !online(dt, t, mac.ID) })
	if code, out := dt.call(t, "POST", "/api/send", "", `{"text":"x","device_id":`+itoa(mac.ID)+`}`); code != http.StatusConflict || out["error"] != "这台电脑不在线" {
		t.Fatalf("offline: %d %v", code, out)
	}
}

func TestUnresponsiveMacIsDropped(t *testing.T) {
	dt := newDeviceTest(t)
	mac, macToken := dt.device(t, "Air", store.PlatformMac)
	// Without a reader the client never answers pings.
	conn, _, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(dt.url, "http")+"/api/devices/stream",
		&websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + macToken}}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	waitFor(t, "online", func() bool { return online(dt, t, mac.ID) })
	waitFor(t, "dropped", func() bool { return !online(dt, t, mac.ID) })
}

func TestOnlyMacsConnect(t *testing.T) {
	dt := newDeviceTest(t)
	_, phone := dt.device(t, "Pixel", store.PlatformAndroid)
	for _, h := range []http.Header{{"Authorization": {"Bearer " + phone}}, {}} {
		_, res, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(dt.url, "http")+"/api/devices/stream",
			&websocket.DialOptions{HTTPHeader: h})
		if err == nil || res == nil || (res.StatusCode != http.StatusForbidden && res.StatusCode != http.StatusUnauthorized) {
			t.Fatalf("headers %v: err = %v", h, err)
		}
	}
}

func TestRevokeDevice(t *testing.T) {
	dt := newDeviceTest(t)
	mac, macToken := dt.device(t, "Air", store.PlatformMac)
	phoneDev, phone := dt.device(t, "Pixel", store.PlatformAndroid)
	m := dt.connect(t, macToken, ok)
	waitFor(t, "online", func() bool { return online(dt, t, mac.ID) })

	// A device cannot revoke another one, only itself.
	if code, _ := dt.call(t, "DELETE", "/api/devices/"+itoa(mac.ID), phone, ""); code != http.StatusForbidden {
		t.Fatalf("phone revoking Mac: %d", code)
	}
	if code, _ := dt.call(t, "DELETE", "/api/devices/"+itoa(phoneDev.ID), phone, ""); code != http.StatusNoContent {
		t.Fatalf("phone signing out: %d", code)
	}
	if code, _ := dt.call(t, "GET", "/api/devices", phone, ""); code != http.StatusUnauthorized {
		t.Fatalf("revoked phone: %d", code)
	}

	// Revoking from the web cuts the push connection at once.
	if code, _ := dt.call(t, "DELETE", "/api/devices/"+itoa(mac.ID), "", ""); code != http.StatusNoContent {
		t.Fatalf("web revoking Mac: %d", code)
	}
	select {
	case _, open := <-m.texts:
		if open {
			t.Fatal("unexpected message")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("connection still open")
	}
	if len(dt.list(t)) != 0 {
		t.Fatalf("devices = %v", dt.list(t))
	}

	// Another user's device is not found.
	other, _, _ := dt.store.CreateDevice(context.Background(), dt.user, "Mini", store.PlatformMac)
	dt.session = dt.signIn(t, "bob")
	if code, _ := dt.call(t, "DELETE", "/api/devices/"+itoa(other.ID), "", ""); code != http.StatusNotFound {
		t.Fatalf("bob revoking alice's device: %d", code)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
