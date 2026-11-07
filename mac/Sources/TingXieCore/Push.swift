import Foundation

/*
 * The push connection (GET /api/devices/stream) carries JSON text messages;
 * see internal/push/push.go on the server:
 *
 *   server → Mac  {"type":"hello","now":…}  once on connect, server clock in ms
 *                 {"type":"insert","id":"…","text":"…","deadline":…}
 *   Mac → server  {"type":"activity"}  the user typed or moved the mouse
 *                 {"type":"ack","id":"…","error":"…"}  error only on failure
 */

public enum ServerMessage: Equatable {
    case hello(now: Int64)
    case insert(id: String, text: String, deadline: Int64?)
    case unknown
}

public func decodeServerMessage(_ text: String) -> ServerMessage {
    struct Raw: Decodable {
        let type: String
        let id: String?
        let text: String?
        let now: Int64?
        let deadline: Int64?
    }
    guard let raw = try? JSONDecoder().decode(Raw.self, from: Data(text.utf8)) else { return .unknown }
    switch raw.type {
    case "hello":
        guard let now = raw.now else { return .unknown }
        return .hello(now: now)
    case "insert":
        guard let id = raw.id, let text = raw.text else { return .unknown }
        return .insert(id: id, text: text, deadline: raw.deadline)
    default:
        return .unknown
    }
}

public let activityMessage = #"{"type":"activity"}"#

public func ackMessage(id: String, error: String? = nil) -> String {
    var body: [String: String] = ["type": "ack", "id": id]
    if let error { body["error"] = error }
    let data = try! JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
    return String(decoding: data, as: UTF8.self)
}

/// The server's clock as seen from this Mac, learnt from hello. The
/// server sends each insert with a deadline; past it, the sender has been
/// told the Mac did not respond, so the text must not be inserted.
public struct ServerClock: Equatable {
    /// Local minus server time, in milliseconds.
    public private(set) var offset: Int64 = 0

    public init() {}

    public mutating func sync(serverNow: Int64, localNow: Int64) {
        offset = localNow - serverNow
    }

    public func isLate(deadline: Int64?, localNow: Int64) -> Bool {
        guard let deadline else { return false }
        return localNow - offset > deadline
    }
}

/// Seconds to wait before reconnect attempt n (0-based): 1, 2, 4 … 30.
public func reconnectDelay(attempt: Int) -> Double {
    min(30, pow(2, Double(min(attempt, 5))))
}

public func streamURL(server: URL) -> URL {
    var c = URLComponents(url: server.appendingPathComponent("api/devices/stream"), resolvingAgainstBaseURL: false)!
    c.scheme = c.scheme == "https" ? "wss" : "ws"
    return c.url!
}

/// Unix time in milliseconds.
public func nowMillis() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }
