import Foundation
import Security

/// What a signed-in Mac remembers. The token lives in the Keychain; the
/// rest rides along so one item holds the whole session.
struct Session: Codable, Equatable {
    var server: URL
    var token: String
    var deviceID: Int64
    var deviceName: String
}

enum Keychain {
    private static let service = "top.onlyquant.tingxie.mac"
    private static let account = "session"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func load() -> Session? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }

    @discardableResult
    static func save(_ session: Session) -> Bool {
        let data = try! JSONEncoder().encode(session)
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}

enum Settings {
    static let defaultServer = URL(string: "https://tingxie.onlyquant.top")!
    private static let d = UserDefaults.standard

    /// The server to sign in to; a signed-in session keeps its own.
    static var server: URL {
        get { d.string(forKey: "server").flatMap(URL.init(string:)) ?? defaultServer }
        set { d.set(newValue.absoluteString, forKey: "server") }
    }

    /// Launch at login is turned on once, on the first run; after that the
    /// menu decides.
    static var launchAtLoginInitialized: Bool {
        get { d.bool(forKey: "launchAtLoginInitialized") }
        set { d.set(newValue, forKey: "launchAtLoginInitialized") }
    }
}
