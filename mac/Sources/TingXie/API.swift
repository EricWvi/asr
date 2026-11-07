import Foundation
import TingXieCore

struct APIError: Error {
    /// nil when the server could not be reached.
    let status: Int?
    let message: String
}

struct Device: Decodable {
    let id: Int64
    let name: String
}

struct Me: Decodable {
    let name: String
    let email: String
}

enum API {
    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 15
        return URLSession(configuration: c)
    }()

    private static func call(_ req: URLRequest) async throws -> (Data, Int) {
        let data: Data, res: URLResponse
        do {
            (data, res) = try await session.data(for: req)
        } catch {
            throw APIError(status: nil, message: "连不上服务器")
        }
        let status = (res as? HTTPURLResponse)?.statusCode ?? 0
        if !(200 ..< 300).contains(status) {
            struct Body: Decodable { let error: String }
            let message = (try? JSONDecoder().decode(Body.self, from: data))?.error ?? "服务器返回 \(status)"
            throw APIError(status: status, message: message)
        }
        return (data, status)
    }

    private static func request(_ server: URL, _ path: String, token: String? = nil, method: String = "GET") -> URLRequest {
        var req = URLRequest(url: server.appendingPathComponent(path))
        req.httpMethod = method
        if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        return req
    }

    /// Step 4 of the sign-in: trade the code for the device token.
    static func exchange(server: URL, code: String, verifier: String) async throws -> (token: String, device: Device) {
        var req = request(server, "auth/device/token", method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["code": code, "code_verifier": verifier])
        struct Body: Decodable { let token: String; let device: Device }
        let (data, _) = try await call(req)
        let body = try JSONDecoder().decode(Body.self, from: data)
        return (body.token, body.device)
    }

    static func me(_ s: Session) async throws -> Me {
        let (data, _) = try await call(request(s.server, "api/me", token: s.token))
        return try JSONDecoder().decode(Me.self, from: data)
    }

    /// Signs this Mac out on the server. A token that is already invalid
    /// counts as signed out.
    static func signOut(_ s: Session) async throws {
        do {
            _ = try await call(request(s.server, "api/devices/\(s.deviceID)", token: s.token, method: "DELETE"))
        } catch let e as APIError where e.status == 401 || e.status == 404 {}
    }

    /// Whether the address answers like this server, for the server dialog.
    static func reachable(_ server: URL) async -> Bool {
        var req = request(server, "api/version")
        req.timeoutInterval = 5
        struct Body: Decodable { let version: String }
        guard let (data, _) = try? await call(req) else { return false }
        return (try? JSONDecoder().decode(Body.self, from: data)) != nil
    }

    /// The version of the latest release, from latest.json.
    static func latestVersion() async throws -> String {
        struct Body: Decodable { let version: String }
        let (data, _) = try await call(URLRequest(url: latestManifestURL))
        return try JSONDecoder().decode(Body.self, from: data).version
    }
}
