import Foundation

/// Where each release puts latest.json; GitHub's latest skips pre-releases.
public let latestManifestURL = URL(string: "https://github.com/EricWvi/asr/releases/latest/download/latest.json")!
public let latestReleasePage = URL(string: "https://github.com/EricWvi/asr/releases/latest")!

/// Compares dotted versions such as "0.7.0" and "0.10.1". A build without a
/// release version ("dev") never offers an update.
public func isNewer(_ candidate: String, than current: String) -> Bool {
    guard let a = versionParts(candidate), let b = versionParts(current) else { return false }
    for i in 0 ..< max(a.count, b.count) {
        let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
        if x != y { return x > y }
    }
    return false
}

/// Release builds check for updates; dev builds ("dev") and pre-releases do not.
public func isReleaseVersion(_ v: String) -> Bool { versionParts(v) != nil }

/// nil for pre-releases ("1.0.0-rc1") and anything that is not numbers.
func versionParts(_ v: String) -> [Int]? {
    let parts = v.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
    guard !parts.isEmpty, parts.allSatisfy({ $0 != nil }) else { return nil }
    return parts.map { $0! }
}

/// The recent inserts shown in the menu, newest first, in memory only.
public struct RecentInserts: Equatable {
    public static let limit = 5
    public struct Item: Equatable {
        public let text: String
        public let at: Date
    }
    public private(set) var items: [Item] = []

    public init() {}

    public mutating func add(_ text: String, at: Date = Date()) {
        items.insert(Item(text: text, at: at), at: 0)
        if items.count > Self.limit { items.removeLast(items.count - Self.limit) }
    }

    public mutating func clear() { items = [] }
}

/// One line of a recent insert for the menu: whitespace folded, cut to
/// `limit` characters with an ellipsis.
public func menuLine(_ text: String, limit: Int = 26) -> String {
    let folded = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    return folded.count > limit ? String(folded.prefix(limit)) + "…" : folded
}

/// "14:32" today, "昨天" yesterday, else "11月5日".
public func menuTime(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
    if calendar.isDate(date, inSameDayAs: now) {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%d:%02d", c.hour!, c.minute!)
    }
    if let y = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: y) { return "昨天" }
    let c = calendar.dateComponents([.month, .day], from: date)
    return "\(c.month!)月\(c.day!)日"
}
