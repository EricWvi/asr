import CryptoKit
import Foundation

/*
 * Signing in, after RFC 8252 (see internal/auth/device.go): the app opens
 * /auth/device/login in the browser with a loopback redirect_uri on
 * 127.0.0.1, the user allows the device on the confirm page, the browser
 * comes back to the loopback server with code and state, and the app
 * exchanges code and the PKCE verifier at /auth/device/token.
 */

public struct PKCE: Equatable {
    public let verifier: String
    public let challenge: String

    public init(verifier: String) {
        self.verifier = verifier
        challenge = base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    public static func make() -> PKCE { PKCE(verifier: randomToken()) }
}

/// 32 random bytes, base64url without padding.
public func randomToken() -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    for i in bytes.indices { bytes[i] = UInt8.random(in: 0 ... 255) }
    return base64URL(Data(bytes))
}

func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

public func loopbackRedirect(port: UInt16) -> String { "http://127.0.0.1:\(port)/callback" }

public func loginURL(server: URL, deviceName: String, redirect: String, state: String, pkce: PKCE) -> URL {
    var c = URLComponents(url: server.appendingPathComponent("auth/device/login"), resolvingAgainstBaseURL: false)!
    c.queryItems = [
        URLQueryItem(name: "platform", value: "mac"),
        URLQueryItem(name: "name", value: deviceName),
        URLQueryItem(name: "redirect_uri", value: redirect),
        URLQueryItem(name: "state", value: state),
        URLQueryItem(name: "code_challenge", value: pkce.challenge),
        URLQueryItem(name: "code_challenge_method", value: "S256"),
    ]
    // URLComponents leaves "+" alone, which a form decoder reads as a space.
    c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
    return c.url!
}

/// The server caps device names at 64 characters.
public func cleanDeviceName(_ name: String?) -> String {
    let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return "Mac" }
    return String(trimmed.prefix(64))
}

public enum CallbackResult: Equatable {
    case code(String)
    case denied
    /// Not the callback, or a callback for another attempt.
    case invalid
}

/// Reads the request target the browser sent to the loopback server, such
/// as "/callback?code=…&state=…".
public func parseCallback(target: String, state: String) -> CallbackResult {
    guard let c = URLComponents(string: target), c.path == "/callback" else { return .invalid }
    let items = c.queryItems ?? []
    func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
    guard value("state") == state else { return .invalid }
    if value("error") != nil { return .denied }
    guard let code = value("code"), !code.isEmpty else { return .invalid }
    return .code(code)
}

/// Accepts "tingxie.example.com", "https://tingxie.example.com/" or
/// "http://192.168.1.5:36750"; nil for anything that is not an http(s)
/// origin. A bare host gets https.
public func normalizeServer(_ input: String) -> URL? {
    var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty { return nil }
    if !text.contains("://") { text = "https://" + text }
    guard var c = URLComponents(string: text),
          let scheme = c.scheme?.lowercased(), scheme == "http" || scheme == "https",
          let host = c.host, !host.isEmpty,
          c.query == nil, c.fragment == nil, c.user == nil
    else { return nil }
    c.scheme = scheme
    c.host = host.lowercased()
    c.path = c.path.hasSuffix("/") ? String(c.path.dropLast()) : c.path
    guard c.path.isEmpty else { return nil }
    return c.url
}

/// Display form of a server: the host, with the port and an http:// for
/// anything that is not plain https.
public func serverLabel(_ url: URL) -> String {
    var s = url.host ?? url.absoluteString
    if let port = url.port { s += ":\(port)" }
    return url.scheme == "https" ? s : "http://" + s
}
