package auth

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"html/template"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"asr/internal/store"
)

/*
 * Native clients sign in with the system browser, after RFC 8252:
 *
 *   1. The client opens /auth/device/login with its platform, name,
 *      redirect_uri, state and a PKCE code_challenge (S256).
 *   2. The browser goes through the usual OIDC login; the device request
 *      rides along in the login cookie and comes back to /auth/callback.
 *   3. /auth/device/confirm asks the signed-in user to allow the device.
 *   4. Allowing sends the browser to redirect_uri?code=…&state=…; the
 *      client checks state and posts code and code_verifier to
 *      /auth/device/token, which returns the device token once.
 *
 * Codes live for a minute and work once. Without the verifier a stolen
 * code is useless, which matters because another Android app can claim
 * the same URL scheme.
 */

const (
	approvalCookie = "asr_device"
	// AndroidRedirect is where the Android client receives the code.
	AndroidRedirect = "tingxie://auth/callback"
	codeLifetime    = time.Minute
	// A device token is touched at most this often to record use.
	deviceTouchAfter = 5 * time.Minute
)

// deviceRequest is what a native client asks for in step 1.
type deviceRequest struct {
	Platform    string `json:"p"`
	Name        string `json:"m"`
	RedirectURI string `json:"r"`
	State       string `json:"s"`
	Challenge   string `json:"c"`
}

func parseDeviceRequest(q url.Values) (deviceRequest, bool) {
	req := deviceRequest{
		Platform:    q.Get("platform"),
		RedirectURI: q.Get("redirect_uri"),
		State:       q.Get("state"),
		Challenge:   q.Get("code_challenge"),
	}
	name, err := store.CleanDeviceName(q.Get("name"))
	req.Name = name
	ok := err == nil &&
		validRedirect(req.Platform, req.RedirectURI) &&
		req.State != "" && len(req.State) <= 256 &&
		q.Get("code_challenge_method") == "S256" && validChallenge(req.Challenge)
	return req, ok
}

// validRedirect allows a loopback port for the Mac client (any port, as
// RFC 8252 asks) and the fixed custom scheme for Android.
func validRedirect(platform, raw string) bool {
	switch platform {
	case store.PlatformAndroid:
		return raw == AndroidRedirect
	case store.PlatformMac:
		u, err := url.Parse(raw)
		return err == nil && u.Scheme == "http" && u.Host == "127.0.0.1:"+u.Port() && u.Port() != "" &&
			u.Path == "/callback" && u.RawQuery == "" && u.Fragment == "" && u.User == nil
	}
	return false
}

// validChallenge accepts the base64url SHA-256 of a verifier.
func validChallenge(c string) bool {
	b, err := base64.RawURLEncoding.DecodeString(c)
	return err == nil && len(b) == sha256.Size
}

// approval waits for the user on the confirm page; issued waits for the
// client to exchange its code.
type approval struct {
	user    store.User
	req     deviceRequest
	expires time.Time
}

type issued struct {
	userID  int64
	req     deviceRequest
	expires time.Time
}

// deviceFlows keeps both in memory: a restart only fails sign-ins that
// are under way.
type deviceFlows struct {
	mu        sync.Mutex
	approvals map[string]approval
	codes     map[string]issued
}

func (f *deviceFlows) addApproval(a approval) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sweep()
	id := random()
	f.approvals[id] = a
	return id
}

func (f *deviceFlows) approval(id string) (approval, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	a, ok := f.approvals[id]
	return a, ok && time.Now().Before(a.expires)
}

func (f *deviceFlows) takeApproval(id string) (approval, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	a, ok := f.approvals[id]
	delete(f.approvals, id)
	return a, ok && time.Now().Before(a.expires)
}

func (f *deviceFlows) addCode(i issued) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sweep()
	code := random()
	f.codes[code] = i
	return code
}

// takeCode consumes the code whatever happens next.
func (f *deviceFlows) takeCode(code string) (issued, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	i, ok := f.codes[code]
	delete(f.codes, code)
	return i, ok && time.Now().Before(i.expires)
}

func (f *deviceFlows) sweep() {
	now := time.Now()
	for id, a := range f.approvals {
		if now.After(a.expires) {
			delete(f.approvals, id)
		}
	}
	for c, i := range f.codes {
		if now.After(i.expires) {
			delete(f.codes, c)
		}
	}
}

func (s *Service) deviceLogin(w http.ResponseWriter, r *http.Request) {
	req, ok := parseDeviceRequest(r.URL.Query())
	if !ok {
		s.devicePage(w, http.StatusBadRequest, devicePageData{Kind: "error", Title: "无法登录", Message: "登录请求无效，请从应用重新登录"})
		return
	}
	if s.devUser != "" {
		user, ok := s.authenticate(w, r)
		if !ok {
			s.devicePage(w, http.StatusInternalServerError, devicePageData{Kind: "error", Title: "无法登录", Message: "请稍后重试"})
			return
		}
		s.startApproval(w, r, user, req)
		return
	}
	s.startLogin(w, r, &req)
}

// startApproval remembers the signed-in user's pending device and shows
// the confirm page.
func (s *Service) startApproval(w http.ResponseWriter, r *http.Request, user store.User, req deviceRequest) {
	id := s.flows.addApproval(approval{user: user, req: req, expires: time.Now().Add(loginLifetime)})
	http.SetCookie(w, &http.Cookie{
		Name:     approvalCookie,
		Value:    id,
		Path:     "/auth/device/",
		MaxAge:   int(loginLifetime.Seconds()),
		HttpOnly: true,
		Secure:   s.secure,
		// Lax, not Strict: the cookie must come along on the redirect that
		// started at the identity provider.
		SameSite: http.SameSiteLaxMode,
	})
	w.Header().Set("Cache-Control", "no-store")
	http.Redirect(w, r, "/auth/device/confirm", http.StatusFound)
}

func (s *Service) deviceConfirm(w http.ResponseWriter, r *http.Request) {
	c, err := r.Cookie(approvalCookie)
	if err != nil {
		s.deviceExpired(w)
		return
	}
	a, ok := s.flows.approval(c.Value)
	if !ok {
		s.deviceExpired(w)
		return
	}
	s.devicePage(w, http.StatusOK, devicePageData{
		Kind:     "ask",
		Approval: c.Value,
		Device:   a.req.Name,
		Platform: a.req.Platform,
		User:     a.user.Name,
		Email:    a.user.Email,
		Avatar:   s.avatarSrc(r.Context(), a.user.ID),
	})
}

// avatarSrc inlines the user's avatar, as the page may be shown without a
// web session to fetch it with. Empty without one.
func (s *Service) avatarSrc(ctx context.Context, userID int64) template.URL {
	u, err := s.store.User(ctx, userID)
	if err != nil {
		slog.Error("look up user", "err", err)
		return ""
	}
	data, err := s.store.ReadAvatar(u)
	if err != nil {
		slog.Error("read avatar", "err", err)
		return ""
	}
	if data == nil {
		return ""
	}
	// Built from sniffed bytes, so safe to mark as a URL.
	return template.URL("data:" + http.DetectContentType(data) + ";base64," + base64.StdEncoding.EncodeToString(data))
}

func (s *Service) deviceApprove(w http.ResponseWriter, r *http.Request) {
	if !s.sameOrigin(r) {
		s.devicePage(w, http.StatusForbidden, devicePageData{Kind: "error", Title: "无法登录", Message: "请求来源不受信任"})
		return
	}
	c, err := r.Cookie(approvalCookie)
	s.clearCookie(w, approvalCookie, "/auth/device/")
	if err != nil || subtle.ConstantTimeCompare([]byte(r.PostFormValue("approval")), []byte(c.Value)) != 1 {
		s.deviceExpired(w)
		return
	}
	a, ok := s.flows.takeApproval(c.Value)
	if !ok {
		s.deviceExpired(w)
		return
	}
	back := url.Values{"state": {a.req.State}}
	data := devicePageData{Kind: "denied"}
	if r.PostFormValue("action") == "allow" {
		back.Set("code", s.flows.addCode(issued{userID: a.user.ID, req: a.req, expires: time.Now().Add(codeLifetime)}))
		data.Kind = "allowed"
	} else {
		back.Set("error", "access_denied")
	}
	data.Redirect = a.req.RedirectURI + "?" + back.Encode()
	s.devicePage(w, http.StatusOK, data)
}

// deviceToken exchanges a code for a device token. It carries no cookie,
// so it needs no origin check.
func (s *Service) deviceToken(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Code     string `json:"code"`
		Verifier string `json:"code_verifier"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "请求格式不正确")
		return
	}
	i, ok := s.flows.takeCode(body.Code)
	sum := sha256.Sum256([]byte(body.Verifier))
	if !ok || subtle.ConstantTimeCompare([]byte(base64.RawURLEncoding.EncodeToString(sum[:])), []byte(i.req.Challenge)) != 1 {
		writeError(w, http.StatusBadRequest, "登录已过期或无效，请重新登录")
		return
	}
	d, token, err := s.store.CreateDevice(r.Context(), i.userID, i.req.Name, i.req.Platform)
	if err != nil {
		slog.Error("create device", "err", err)
		writeError(w, http.StatusInternalServerError, "服务器内部错误")
		return
	}
	slog.Info("device signed in", "user", i.userID, "device", d.ID, "platform", d.Platform)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(map[string]any{"token": token, "device": d})
}

// bearer authenticates a native client by its device token.
func (s *Service) bearer(r *http.Request, header string) (store.User, store.Device, bool) {
	token, ok := strings.CutPrefix(header, "Bearer ")
	if !ok || token == "" {
		return store.User{}, store.Device{}, false
	}
	d, u, err := s.store.DeviceUser(r.Context(), token)
	if err != nil {
		if err != store.ErrNotFound {
			slog.Error("look up device", "err", err)
		}
		return u, d, false
	}
	if time.Since(time.UnixMilli(d.LastUsedAt)) > deviceTouchAfter {
		if err := s.store.TouchDevice(r.Context(), d.ID); err != nil {
			slog.Error("touch device", "err", err)
		}
	}
	return u, d, true
}

func (s *Service) deviceExpired(w http.ResponseWriter) {
	s.devicePage(w, http.StatusBadRequest, devicePageData{Kind: "expired", Title: "登录已过期", Message: "请回到应用重新登录"})
}

type devicePageData struct {
	Kind     string // ask, allowed, denied, expired or error
	Approval string
	Device   string
	Platform string
	User     string
	Email    string
	Avatar   template.URL
	Redirect string
	Title    string
	Message  string
}

func (d devicePageData) Initial() string {
	for _, r := range strings.ToUpper(d.User) {
		return string(r)
	}
	return "?"
}

// RedirectURL marks the client's address as safe for href; it was checked
// against the allow list in parseDeviceRequest.
func (d devicePageData) RedirectURL() template.URL { return template.URL(d.Redirect) }

func (s *Service) devicePage(w http.ResponseWriter, status int, data devicePageData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(status)
	if err := devicePageTmpl.Execute(w, data); err != nil {
		slog.Error("render device page", "err", err)
	}
}

// The look follows design/device-auth.html.
var devicePageTmpl = template.Must(template.New("device").Parse(`<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>听 · 写 设备登录</title>
<style>
:root{--bg:#faf8f5;--surface:#fff;--ink:#1d1d1f;--ink-2:#6e6e73;--line:#ebe8e3;--chip:#f2f1ef;--accent:#1a7cf5;--periwinkle:#b6c4f4;
--shadow-sm:0 1px 2px rgb(0 0 0/.05),0 2px 8px -2px rgb(0 0 0/.06);--shadow-md:0 1px 2px rgb(0 0 0/.04),0 12px 32px -10px rgb(0 0 0/.14)}
@media (prefers-color-scheme:dark){:root{--bg:#1b1b1d;--surface:#2a2a2d;--ink:#f2f2f4;--ink-2:#a0a0a7;--line:#3a3a3e;--chip:#36363a;--accent:#3b8ef7;
--shadow-sm:0 1px 2px rgb(0 0 0/.3);--shadow-md:0 1px 2px rgb(0 0 0/.3),0 12px 32px -10px rgb(0 0 0/.5)}.brand-mark{background:var(--chip)!important}}
*{box-sizing:border-box}html,body{height:100%;margin:0}
body{display:grid;place-items:center;padding:24px;background:var(--bg);color:var(--ink);font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC","Hiragino Sans GB","Microsoft YaHei","Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}
button{font:inherit;color:inherit;background:none;border:0;padding:0;cursor:pointer}button:focus:not(:focus-visible){outline:none}
svg{display:block}.icon{width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round}
.card{width:100%;max-width:380px;padding:28px 28px 24px;border-radius:20px;background:var(--surface);box-shadow:inset 0 0 0 1px var(--line),var(--shadow-md)}
.brand{display:flex;align-items:center;gap:14px;margin-bottom:22px}
.brand-mark{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--surface);box-shadow:var(--shadow-sm)}.logo{width:24px}
.brand-name{font:700 18px/1 "Songti SC","Noto Serif SC","Source Han Serif SC",serif;letter-spacing:.06em}
h1{margin:0 0 16px;font-size:18px;font-weight:600;line-height:1.4}
.rows{border-radius:12px;box-shadow:inset 0 0 0 1px var(--line)}
.item{display:flex;align-items:center;gap:12px;padding:12px 14px}.item+.item{border-top:1px solid var(--line)}
.item-icon{width:32px;height:32px;border-radius:9px;flex:none;display:grid;place-items:center;background:var(--chip);color:var(--ink-2)}
.avatar{width:32px;height:32px;border-radius:50%;flex:none;display:grid;place-items:center;overflow:hidden;font-size:13px;font-weight:600;background:var(--periwinkle);color:#1d1d1f}.avatar img{width:100%;height:100%;object-fit:cover}
.item-body{min-width:0}.item-name,.item-sub{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.item-sub{font-size:12px;color:var(--ink-2)}
.actions{display:flex;gap:10px;margin-top:22px}
.btn{flex:1;height:40px;border-radius:10px;font-size:14px;font-weight:500;transition:background .15s,transform .12s}.btn:active{transform:scale(.98)}
.btn.secondary{background:var(--chip)}.btn.secondary:hover{background:color-mix(in oklch,var(--chip),var(--ink) 6%)}
.btn.primary{background:var(--accent);color:#fff}.btn.primary:hover{background:color-mix(in oklch,var(--accent),#000 8%)}
.result{text-align:center;padding:8px 0 4px}
.mark{width:44px;height:44px;margin:0 auto 14px;border-radius:50%;display:grid;place-items:center;background:var(--chip);color:var(--ink-2)}
.mark .icon{width:20px;height:20px}.mark.ok{background:color-mix(in oklch,var(--accent) 14%,var(--surface));color:var(--accent)}
.result h1{margin-bottom:6px}.result p{margin:0;font-size:13px;color:var(--ink-2)}
.result p a{color:var(--accent);text-decoration:none}.result p a:hover{text-decoration:underline}
</style></head><body>
<main class="card">
<div class="brand"><div class="brand-mark"><svg class="logo" viewBox="0 1 398 383"><path fill="#fde8a7" d="M0.0 123.5A58.5 58.5 0 0 1 35.3 69.8L186.1 4.6A45.8 45.8 0 0 1 250.0 46.6L250.0 334.3A49.8 49.8 0 0 1 183.5 381.2L36.5 328.6A55.0 55.0 0 0 1 0.0 276.8Z"/><path fill="#fec6ab" d="M264.8 48.8A9.8 9.8 0 0 1 274.5 39.0L281.0 39.0A46.0 46.0 0 0 1 327.0 85.0L327.0 298.8A46.0 46.0 0 0 1 281.0 344.8L274.5 344.8A9.8 9.8 0 0 1 264.8 335.0Z"/><path fill="#bcc6fa" d="M341.8 87.0A8.2 8.2 0 0 1 350.0 78.8L355.2 78.8A42.5 42.5 0 0 1 397.8 121.2L397.8 259.8A42.5 42.5 0 0 1 355.2 302.2L350.0 302.2A8.2 8.2 0 0 1 341.8 294.0Z"/><path fill="#f5c258" d="M57.6 132.4L179.6 116.2A20.0 20.0 0 1 1 183.8 155.9L61.0 165.2A16.5 16.5 0 0 1 57.6 132.4Z"/><path fill="#f5c258" d="M59.1 184.8L154.8 183.8A18.5 18.5 0 1 1 154.3 220.7L58.7 217.2A16.2 16.2 0 0 1 59.1 184.8Z"/><path fill="#f5c258" d="M60.9 238.3L99.7 241.0A17.0 17.0 0 1 1 96.9 274.9L58.2 271.2A16.5 16.5 0 0 1 60.9 238.3Z"/></svg></div><div class="brand-name">听 · 写</div></div>
{{if eq .Kind "ask"}}
<h1>允许这台设备登录？</h1>
<div class="rows">
<div class="item"><span class="item-icon">{{if eq .Platform "android"}}<svg class="icon" viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>{{else}}<svg class="icon" viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 19h20"/></svg>{{end}}</span>
<span class="item-body"><div class="item-name">{{.Device}}</div><div class="item-sub">{{if eq .Platform "android"}}安卓客户端{{else}}Mac 客户端{{end}}</div></span></div>
<div class="item"><span class="avatar">{{if .Avatar}}<img src="{{.Avatar}}" alt="">{{else}}{{.Initial}}{{end}}</span><span class="item-body"><div class="item-name">{{.User}}</div>{{if .Email}}<div class="item-sub">{{.Email}}</div>{{end}}</span></div>
</div>
<form method="post" action="/auth/device/approve" class="actions">
<input type="hidden" name="approval" value="{{.Approval}}">
<button class="btn secondary" name="action" value="deny">取消</button>
<button class="btn primary" name="action" value="allow">允许</button>
</form>
{{else if eq .Kind "allowed"}}
<section class="result"><div class="mark ok"><svg class="icon" viewBox="0 0 24 24"><path d="M5 12l5 5 9-10"/></svg></div>
<h1>已允许登录</h1><p>正在返回应用，没有跳转的话<a href="{{.RedirectURL}}">点这里</a></p></section>
<script>location.replace({{.Redirect}})</script>
{{else if eq .Kind "denied"}}
<section class="result"><div class="mark"><svg class="icon" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></div><h1>已取消登录</h1></section>
<script>location.replace({{.Redirect}})</script>
{{else}}
<section class="result"><div class="mark"><svg class="icon" viewBox="0 0 24 24">{{if eq .Kind "expired"}}<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>{{else}}<path d="M6 6l12 12M18 6L6 18"/>{{end}}</svg></div>
<h1>{{.Title}}</h1><p>{{.Message}}</p></section>
{{end}}
</main></body></html>`))
