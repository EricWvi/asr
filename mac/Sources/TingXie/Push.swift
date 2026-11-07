import AppKit
import Network
import OSLog
import TingXieCore

let log = Logger(subsystem: "top.onlyquant.tingxie.mac", category: "push")

/// The long-lived connection to the server. It reconnects on its own with
/// a growing delay, at once after the Mac wakes or the network comes back,
/// and gives up only when the token is no longer accepted.
@MainActor
final class PushConnection: NSObject, URLSessionWebSocketDelegate {
    enum Status { case connecting, connected, offline }

    var onStatus: (Status) -> Void = { _ in }
    /// Inserts the text; returns the reason in Chinese if it was not inserted.
    var onInsert: (String) -> String? = { _ in nil }
    var onUnauthorized: () -> Void = {}

    private let session: Session
    private var urlSession: URLSession!
    private var task: URLSessionWebSocketTask?
    private var loop: Task<Void, Never>?
    private var clock = ServerClock()
    private var opened = false
    private var skipDelay = false
    private var retryDelay: Task<Void, Never>?
    private var activityTimer: Timer?
    private var pathMonitor: NWPathMonitor?
    private var wakeObserver: NSObjectProtocol?

    init(session: Session) {
        self.session = session
        super.init()
        urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    }

    func start() {
        loop = Task { [weak self] in await self?.run() }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.reconnectNow() }
        }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in if self?.opened == false { self?.reconnectNow() } }
        }
        monitor.start(queue: .main)
        pathMonitor = monitor
        // Report activity at most every 5 s while the user is at this Mac;
        // reading the idle time needs no permission.
        activityTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.reportActivity() }
        }
    }

    func stop() {
        loop?.cancel()
        loop = nil
        task?.cancel(with: .normalClosure, reason: nil)
        activityTimer?.invalidate()
        pathMonitor?.cancel()
        if let wakeObserver { NSWorkspace.shared.notificationCenter.removeObserver(wakeObserver) }
        urlSession.invalidateAndCancel()
    }

    /// After a wake the old socket may look open but be dead, so it is
    /// dropped; a pending retry delay is cut short. A connection that is
    /// still being set up is left alone.
    private func reconnectNow() {
        if opened {
            skipDelay = true
            task?.cancel(with: .goingAway, reason: nil)
        } else if let retryDelay {
            skipDelay = true
            retryDelay.cancel()
        }
    }

    private func run() async {
        var attempt = 0
        while !Task.isCancelled {
            onStatus(attempt == 0 ? .connecting : .offline)
            var req = URLRequest(url: streamURL(server: session.server))
            req.setValue("Bearer " + session.token, forHTTPHeaderField: "Authorization")
            let ws = urlSession.webSocketTask(with: req)
            task = ws
            opened = false
            ws.resume()
            log.info("connecting to \(self.session.server.absoluteString, privacy: .public)")
            let keepAlive = Task { await self.keepAlive(ws) }
            do {
                while true {
                    guard case let .string(text) = try await receive(ws) else { continue }
                    await handle(text, on: ws)
                }
            } catch {
                let status = (ws.response as? HTTPURLResponse)?.statusCode ?? 0
                log.info("connection ended: \(error.localizedDescription, privacy: .public), status \(status), close \(ws.closeCode.rawValue)")
            }
            keepAlive.cancel()
            let wasOpen = opened
            opened = false
            if Task.isCancelled { return }
            if isUnauthorized(ws) {
                onUnauthorized()
                return
            }
            ws.cancel(with: .goingAway, reason: nil)
            if wasOpen { attempt = 0 } else { attempt += 1 }
            if skipDelay {
                skipDelay = false
                continue
            }
            // A dropped connection is retried soon; a failing one backs off.
            let delay = wasOpen ? 1 : reconnectDelay(attempt: attempt - 1)
            onStatus(wasOpen ? .connecting : .offline)
            let wait = Task { _ = try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
            retryDelay = wait
            await wait.value
            retryDelay = nil
            skipDelay = false
        }
    }

    private var pendingReceive: CheckedContinuation<URLSessionWebSocketTask.Message, Error>?

    /// receive() of URLSessionWebSocketTask never returns on a task that
    /// was cancelled before it connected, so the end of the task, reported
    /// to the delegate, also ends the wait.
    private func receive(_ ws: URLSessionWebSocketTask) async throws -> URLSessionWebSocketTask.Message {
        if ws.state == .completed || ws.state == .canceling { throw ws.error ?? CancellationError() }
        return try await withCheckedThrowingContinuation { c in
            pendingReceive = c
            ws.receive { result in
                Task { @MainActor in self.finishReceive(result) }
            }
        }
    }

    private func finishReceive(_ result: Result<URLSessionWebSocketTask.Message, Error>) {
        guard let c = pendingReceive else { return }
        pendingReceive = nil
        c.resume(with: result)
    }

    private func handle(_ text: String, on ws: URLSessionWebSocketTask) async {
        switch decodeServerMessage(text) {
        case let .hello(now):
            clock.sync(serverNow: now, localNow: nowMillis())
        case let .insert(id, body, deadline):
            // Checked here, on the main thread: a Mac that was asleep or busy
            // gets the message late, after the sender was told it failed.
            let error = clock.isLate(deadline: deadline, localNow: nowMillis())
                ? "电脑没有及时响应，没有插入"
                : onInsert(body)
            try? await ws.send(.string(ackMessage(id: id, error: error)))
        case .unknown:
            break
        }
    }

    /// The server pings too, but only the client notices a dead network
    /// quickly enough to reconnect.
    private func keepAlive(_ ws: URLSessionWebSocketTask) async {
        // Give up on a connection that does not open.
        try? await Task.sleep(nanoseconds: 20_000_000_000)
        if Task.isCancelled { return }
        if !opened {
            ws.cancel(with: .goingAway, reason: nil)
            return
        }
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 25_000_000_000)
            if Task.isCancelled { return }
            let ok = await withTaskGroup(of: Bool.self) { group in
                group.addTask {
                    await withCheckedContinuation { c in ws.sendPing { c.resume(returning: $0 == nil) } }
                }
                group.addTask {
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                    return false
                }
                let first = await group.next() ?? false
                group.cancelAll()
                return first
            }
            if !ok {
                ws.cancel(with: .goingAway, reason: nil)
                return
            }
        }
    }

    private func isUnauthorized(_ ws: URLSessionWebSocketTask) -> Bool {
        if let status = (ws.response as? HTTPURLResponse)?.statusCode, status == 401 || status == 403 { return true }
        let reason = ws.closeReason.map { String(decoding: $0, as: UTF8.self) }
        return ws.closeCode == .policyViolation && reason == "device revoked"
    }

    private func reportActivity() {
        guard opened, let task else { return }
        let idle = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: ~0)!)
        if idle < 5 { task.send(.string(activityMessage)) { _ in } }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        MainActor.assumeIsolated {
            guard task === self.task else { return }
            finishReceive(.failure(error ?? CancellationError()))
        }
    }

    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        MainActor.assumeIsolated {
            guard webSocketTask === task else { return }
            log.info("connected")
            opened = true
            onStatus(.connected)
            reportActivity()
        }
    }
}
