import Foundation

/// What a signed-in Mac remembers.
struct Session: Codable, Equatable {
    var server: URL
    var token: String
    var deviceID: Int64
    var deviceName: String
}

/// The session lives in a file only this user can read, not in the
/// Keychain: the login keychain ties an item to the exact build of an app
/// without a Team ID (ours is self-signed), so every update would ask for
/// the login password. The token can be revoked on the web.
enum SessionStore {
    private static var dir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("top.onlyquant.tingxie.mac", isDirectory: true)
    }

    private static var file: URL { dir.appendingPathComponent("session.json") }

    static func load() -> Session? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }

    /// Written to a 0600 temporary file and renamed, so the token is never
    /// readable by others, not even for a moment.
    @discardableResult
    static func save(_ session: Session) -> Bool {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let tmp = dir.appendingPathComponent(".session-\(UUID().uuidString)")
            guard fm.createFile(atPath: tmp.path, contents: try JSONEncoder().encode(session), attributes: [.posixPermissions: 0o600]) else { return false }
            guard rename(tmp.path, file.path) == 0 else {
                try? fm.removeItem(at: tmp)
                return false
            }
            return true
        } catch {
            return false
        }
    }

    static func delete() {
        try? FileManager.default.removeItem(at: file)
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
