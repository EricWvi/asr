import Foundation
import Testing
@testable import TingXieCore

@Suite struct PushTests {
    @Test func decodesServerMessages() {
        #expect(decodeServerMessage(#"{"type":"hello","now":1700000000000}"#) == .hello(now: 1_700_000_000_000))
        #expect(decodeServerMessage(#"{"type":"insert","id":"a1","text":"你好","deadline":5}"#) == .insert(id: "a1", text: "你好", deadline: 5))
        // An older server sends no deadline.
        #expect(decodeServerMessage(#"{"type":"insert","id":"a1","text":"x"}"#) == .insert(id: "a1", text: "x", deadline: nil))
        #expect(decodeServerMessage(#"{"type":"later"}"#) == .unknown)
        #expect(decodeServerMessage("not json") == .unknown)
    }

    @Test func encodesAcks() {
        #expect(ackMessage(id: "a1") == #"{"id":"a1","type":"ack"}"#)
        #expect(ackMessage(id: "a1", error: "没有插入") == #"{"error":"没有插入","id":"a1","type":"ack"}"#)
    }

    @Test func deadlineUsesTheServerClock() {
        var clock = ServerClock()
        // This Mac runs 2 s ahead of the server.
        clock.sync(serverNow: 10_000, localNow: 12_000)
        #expect(!clock.isLate(deadline: 14_000, localNow: 15_900))
        #expect(clock.isLate(deadline: 14_000, localNow: 16_001))
        #expect(!clock.isLate(deadline: nil, localNow: .max))
    }

    @Test func backsOffUpToThirtySeconds() {
        #expect((0 ..< 8).map { reconnectDelay(attempt: $0) } == [1, 2, 4, 8, 16, 30, 30, 30])
    }

    @Test func streamURLFollowsTheScheme() {
        #expect(streamURL(server: URL(string: "https://tingxie.onlyquant.top")!).absoluteString == "wss://tingxie.onlyquant.top/api/devices/stream")
        #expect(streamURL(server: URL(string: "http://127.0.0.1:36750")!).absoluteString == "ws://127.0.0.1:36750/api/devices/stream")
    }
}

@Suite struct LoginTests {
    @Test func pkceMatchesRFC7636() {
        // The example in RFC 7636, appendix B.
        let p = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        #expect(p.challenge == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        #expect(PKCE.make() != PKCE.make())
    }

    @Test func buildsTheLoginURL() {
        let url = loginURL(server: URL(string: "https://t.example")!, deviceName: "Eric 的 MacBook+Pro",
                           redirect: loopbackRedirect(port: 50123), state: "s1", pkce: PKCE(verifier: "v"))
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        #expect(c.path == "/auth/device/login")
        let q = Dictionary(uniqueKeysWithValues: c.queryItems!.map { ($0.name, $0.value!) })
        #expect(q["platform"] == "mac")
        #expect(q["redirect_uri"] == "http://127.0.0.1:50123/callback")
        #expect(q["code_challenge_method"] == "S256")
        #expect(url.absoluteString.contains("MacBook%2BPro"))
    }

    @Test func readsTheCallback() {
        #expect(parseCallback(target: "/callback?code=c1&state=s1", state: "s1") == .code("c1"))
        #expect(parseCallback(target: "/callback?error=access_denied&state=s1", state: "s1") == .denied)
        #expect(parseCallback(target: "/callback?code=c1&state=other", state: "s1") == .invalid)
        #expect(parseCallback(target: "/favicon.ico", state: "s1") == .invalid)
    }

    @Test func cleansDeviceNames() {
        #expect(cleanDeviceName("  Eric 的 MacBook Pro ") == "Eric 的 MacBook Pro")
        #expect(cleanDeviceName(nil) == "Mac")
        #expect(cleanDeviceName(String(repeating: "长", count: 80)).count == 64)
    }

    @Test func normalizesServers() {
        #expect(normalizeServer("tingxie.onlyquant.top")?.absoluteString == "https://tingxie.onlyquant.top")
        #expect(normalizeServer(" HTTPS://Tingxie.OnlyQuant.top/ ")?.absoluteString == "https://tingxie.onlyquant.top")
        #expect(normalizeServer("http://192.168.1.5:36750")?.absoluteString == "http://192.168.1.5:36750")
        #expect(normalizeServer("ftp://x") == nil)
        #expect(normalizeServer("https://x/path") == nil)
        #expect(normalizeServer("") == nil)
        #expect(serverLabel(URL(string: "https://tingxie.onlyquant.top")!) == "tingxie.onlyquant.top")
        #expect(serverLabel(URL(string: "http://192.168.1.5:36750")!) == "http://192.168.1.5:36750")
    }
}

@Suite struct MenuTests {
    @Test func comparesVersions() {
        #expect(isNewer("0.8.0", than: "0.7.9"))
        #expect(isNewer("0.10.0", than: "0.9.1"))
        #expect(!isNewer("0.7.0", than: "0.7.0"))
        #expect(!isNewer("0.8.0-rc1", than: "0.7.0"))
        #expect(!isNewer("0.8.0", than: "dev"))
    }

    @Test func keepsFiveRecentInserts() {
        var r = RecentInserts()
        for i in 1 ... 7 { r.add("第\(i)句") }
        #expect(r.items.map(\.text) == ["第7句", "第6句", "第5句", "第4句", "第3句"])
    }

    @Test func foldsMenuLines() {
        #expect(menuLine("好的，\n收到。") == "好的， 收到。")
        #expect(menuLine("一二三四五六", limit: 4) == "一二三四…")
    }

    @Test func formatsMenuTimes() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let now = cal.date(from: DateComponents(year: 2026, month: 11, day: 7, hour: 14, minute: 32))!
        #expect(menuTime(cal.date(byAdding: .minute, value: -27, to: now)!, now: now, calendar: cal) == "14:05")
        #expect(menuTime(cal.date(byAdding: .day, value: -1, to: now)!, now: now, calendar: cal) == "昨天")
        #expect(menuTime(cal.date(byAdding: .day, value: -2, to: now)!, now: now, calendar: cal) == "11月5日")
    }
}
