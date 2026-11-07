import AppKit
import Network
import TingXieCore

enum LoginError: Error {
    case cancelled, denied, timedOut
    case failed(String)
}

/// One sign-in through the browser. The browser comes back to a small
/// HTTP server on 127.0.0.1 that answers the callback with a page of its
/// own; see TingXieCore/Login.swift for the protocol.
@MainActor
final class LoginFlow {
    private var listener: NWListener?
    private var continuation: CheckedContinuation<Session, Error>?
    private var timeout: Task<Void, Never>?

    func run(server: URL) async throws -> Session {
        let pkce = PKCE.make()
        let state = randomToken()
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: params)
        self.listener = listener
        defer { stop() }

        return try await withCheckedThrowingContinuation { c in
            continuation = c
            listener.newConnectionHandler = { [weak self] conn in
                Task { @MainActor in self?.accept(conn, server: server, state: state, pkce: pkce) }
            }
            listener.stateUpdateHandler = { [weak self] st in
                Task { @MainActor in
                    guard let self else { return }
                    switch st {
                    case .ready:
                        guard let port = listener.port?.rawValue else { return }
                        let url = loginURL(server: server, deviceName: cleanDeviceName(Host.current().localizedName),
                                           redirect: loopbackRedirect(port: port), state: state, pkce: pkce)
                        NSWorkspace.shared.open(url)
                    case .failed:
                        self.finish(.failure(LoginError.failed("无法开始登录")))
                    default: break
                    }
                }
            }
            listener.start(queue: .main)
            // The server forgets the request after 10 minutes too.
            timeout = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 10 * 60 * 1_000_000_000)
                if !Task.isCancelled { self?.finish(.failure(LoginError.timedOut)) }
            }
        }
    }

    func cancel() { finish(.failure(LoginError.cancelled)) }

    private func finish(_ result: Result<Session, Error>) {
        guard let c = continuation else { return }
        continuation = nil
        timeout?.cancel()
        c.resume(with: result)
    }

    private func stop() {
        // Let the last response flush before the port goes away.
        let l = listener
        listener = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { l?.cancel() }
    }

    private func accept(_ conn: NWConnection, server: URL, state: String, pkce: PKCE) {
        conn.start(queue: .main)
        readRequest(conn, buffer: Data()) { [weak self] target in
            guard let self else { return }
            guard let target, self.continuation != nil else {
                Self.respond(conn, status: "404 Not Found", page: nil)
                return
            }
            switch parseCallback(target: target, state: state) {
            case .invalid:
                Self.respond(conn, status: "404 Not Found", page: nil)
            case .denied:
                Self.respond(conn, status: "200 OK", page: .denied)
                self.finish(.failure(LoginError.denied))
            case let .code(code):
                Task { @MainActor in
                    do {
                        let (token, device) = try await API.exchange(server: server, code: code, verifier: pkce.verifier)
                        Self.respond(conn, status: "200 OK", page: .ok)
                        self.finish(.success(Session(server: server, token: token, deviceID: device.id, deviceName: device.name)))
                    } catch {
                        Self.respond(conn, status: "200 OK", page: .failed)
                        self.finish(.failure(LoginError.failed((error as? APIError)?.message ?? "登录失败")))
                    }
                }
            }
        }
    }

    /// Reads up to the end of the request headers and returns the target
    /// of a GET, or nil.
    private func readRequest(_ conn: NWConnection, buffer: Data, done: @escaping @MainActor (String?) -> Void) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, isComplete, error in
            Task { @MainActor in
                var buf = buffer
                if let data { buf.append(data) }
                if let end = buf.range(of: Data("\r\n\r\n".utf8)) {
                    let head = String(decoding: buf[..<end.lowerBound], as: UTF8.self)
                    let parts = head.split(separator: "\r\n").first?.split(separator: " ") ?? []
                    done(parts.count >= 2 && parts[0] == "GET" ? String(parts[1]) : nil)
                } else if error != nil || isComplete || buf.count > 16384 {
                    done(nil)
                } else {
                    self.readRequest(conn, buffer: buf, done: done)
                }
            }
        }
    }

    private static func respond(_ conn: NWConnection, status: String, page: CallbackPage?) {
        let body = Data((page?.html ?? "").utf8)
        let head = "HTTP/1.1 \(status)\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.count)\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        conn.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in conn.cancel() })
    }
}

/// The page the browser shows after coming back, in the look of the
/// confirm page (internal/auth/device.go, design/mac.html #callback-*).
enum CallbackPage {
    case ok, denied, failed

    var html: String {
        let (ok, title, message): (Bool, String, String) = switch self {
        case .ok: (true, "已登录", "可以关闭这个页面，回到听·写")
        case .denied: (false, "已取消登录", "可以关闭这个页面")
        case .failed: (false, "登录失败", "请回到听·写重新登录")
        }
        let mark = ok ? #"<path d="M5 12l5 5 9-10"/>"# : #"<path d="M6 6l12 12M18 6L6 18"/>"#
        return """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="color-scheme" content="light dark"><title>听 · 写</title><style>
        :root{--bg:#faf8f5;--surface:#fff;--ink:#1d1d1f;--ink-2:#6e6e73;--line:#ebe8e3;--chip:#f2f1ef;--accent:#1a7cf5;
        --shadow-sm:0 1px 2px rgb(0 0 0/.05),0 2px 8px -2px rgb(0 0 0/.06);--shadow-md:0 1px 2px rgb(0 0 0/.04),0 12px 32px -10px rgb(0 0 0/.14)}
        @media (prefers-color-scheme:dark){:root{--bg:#1b1b1d;--surface:#2a2a2d;--ink:#f2f2f4;--ink-2:#a0a0a7;--line:#3a3a3e;--chip:#36363a;--accent:#3b8ef7;
        --shadow-sm:0 1px 2px rgb(0 0 0/.3);--shadow-md:0 1px 2px rgb(0 0 0/.3),0 12px 32px -10px rgb(0 0 0/.5)}.brand-mark{background:var(--chip)!important}}
        *{box-sizing:border-box}html,body{height:100%;margin:0}
        .page{min-height:100%;display:grid;place-items:center;padding:24px}
        body{background:var(--bg);color:var(--ink);font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;-webkit-font-smoothing:antialiased}
        svg{display:block}.icon{width:20px;height:20px;stroke:currentColor;fill:none;stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round}
        .card{width:100%;max-width:380px;padding:28px 28px 24px;border-radius:20px;background:var(--surface);box-shadow:inset 0 0 0 1px var(--line),var(--shadow-md)}
        .brand{display:flex;align-items:center;gap:14px;margin-bottom:22px}
        .brand-mark{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--surface);box-shadow:var(--shadow-sm)}.logo{width:24px}
        .brand-name{font:700 18px/1 "Songti SC","Noto Serif SC",serif;display:inline-flex;align-items:center;gap:4px}
        .brand-dot{width:3px;height:3px;border-radius:50%;background:currentColor}
        .result{text-align:center;padding:8px 0 4px}
        .mark{width:44px;height:44px;margin:0 auto 14px;border-radius:50%;display:grid;place-items:center;background:var(--chip);color:var(--ink-2)}
        .mark.ok{background:color-mix(in oklch,var(--accent) 14%,var(--surface));color:var(--accent)}
        h1{margin:0 0 6px;font-size:18px;font-weight:600;line-height:1.4}p{margin:0;font-size:13px;color:var(--ink-2)}
        </style></head><body><div class="page"><main class="card">
        <div class="brand"><div class="brand-mark">\(logoSVG)</div><div class="brand-name" aria-label="听·写"><span aria-hidden="true">听</span><span class="brand-dot" aria-hidden="true"></span><span aria-hidden="true">写</span></div></div>
        <section class="result"><div class="mark\(ok ? " ok" : "")"><svg class="icon" viewBox="0 0 24 24">\(mark)</svg></div><h1>\(title)</h1><p>\(message)</p></section>
        </main></div></body></html>
        """
    }
}

private let logoSVG = ##"<svg class="logo" viewBox="0 1 398 383"><path fill="#fde8a7" d="M0.0 123.5A58.5 58.5 0 0 1 35.3 69.8L186.1 4.6A45.8 45.8 0 0 1 250.0 46.6L250.0 334.3A49.8 49.8 0 0 1 183.5 381.2L36.5 328.6A55.0 55.0 0 0 1 0.0 276.8Z"/><path fill="#fec6ab" d="M264.8 48.8A9.8 9.8 0 0 1 274.5 39.0L281.0 39.0A46.0 46.0 0 0 1 327.0 85.0L327.0 298.8A46.0 46.0 0 0 1 281.0 344.8L274.5 344.8A9.8 9.8 0 0 1 264.8 335.0Z"/><path fill="#bcc6fa" d="M341.8 87.0A8.2 8.2 0 0 1 350.0 78.8L355.2 78.8A42.5 42.5 0 0 1 397.8 121.2L397.8 259.8A42.5 42.5 0 0 1 355.2 302.2L350.0 302.2A8.2 8.2 0 0 1 341.8 294.0Z"/><path fill="#f5c258" d="M57.6 132.4L179.6 116.2A20.0 20.0 0 1 1 183.8 155.9L61.0 165.2A16.5 16.5 0 0 1 57.6 132.4Z"/><path fill="#f5c258" d="M59.1 184.8L154.8 183.8A18.5 18.5 0 1 1 154.3 220.7L58.7 217.2A16.2 16.2 0 0 1 59.1 184.8Z"/><path fill="#f5c258" d="M60.9 238.3L99.7 241.0A17.0 17.0 0 1 1 96.9 274.9L58.2 271.2A16.5 16.5 0 0 1 60.9 238.3Z"/></svg>"##
