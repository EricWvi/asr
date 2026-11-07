import AppKit
import ServiceManagement
import TingXieCore

/// Everything the menu shows, and the actions behind it.
@MainActor
final class AppModel {
    enum Phase: Equatable {
        case loggedOut(message: String?)
        case loggingIn
        case connecting
        case connected
        case offline
    }

    private(set) var phase: Phase = .loggedOut(message: nil)
    private(set) var session: Session?
    private(set) var me: Me?
    private(set) var recent = RecentInserts()
    private(set) var trusted = true
    private(set) var update: String?

    /// Called after any change; the menu rebuilds itself.
    var onChange: () -> Void = {}

    let inserter = Inserter()
    private var push: PushConnection?
    private var login: LoginFlow?
    private var permissionTimer: Timer?

    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"

    func start() {
        initLaunchAtLogin()
        trusted = inserter.trusted
        if !trusted { watchPermission() }
        if let s = Keychain.load() {
            signedIn(s, fresh: false)
        }
        onChange()
        scheduleUpdateChecks()
    }

    // MARK: Sign-in

    var server: URL { session?.server ?? Settings.server }

    func signIn() {
        guard login == nil else { return }
        let flow = LoginFlow()
        login = flow
        phase = .loggingIn
        onChange()
        Task {
            defer { login = nil }
            do {
                let s = try await flow.run(server: Settings.server)
                guard Keychain.save(s) else {
                    phase = .loggedOut(message: "无法保存登录信息")
                    onChange()
                    return
                }
                signedIn(s, fresh: true)
            } catch LoginError.cancelled {
                phase = .loggedOut(message: nil)
            } catch LoginError.denied {
                phase = .loggedOut(message: "已取消登录")
            } catch LoginError.timedOut {
                phase = .loggedOut(message: "登录超时，请重试")
            } catch let LoginError.failed(message) {
                phase = .loggedOut(message: message)
            } catch {
                phase = .loggedOut(message: "登录失败，请重试")
            }
            onChange()
        }
    }

    func cancelSignIn() { login?.cancel() }

    private func signedIn(_ s: Session, fresh: Bool) {
        session = s
        phase = .connecting
        let p = PushConnection(session: s)
        p.onStatus = { [weak self] st in
            guard let self, self.session != nil else { return }
            switch st {
            case .connecting: self.phase = .connecting
            case .connected: self.phase = .connected
            case .offline: self.phase = .offline
            }
            self.onChange()
            if st == .connected, self.me == nil { self.loadMe() }
        }
        // insert returns nil on success, so no `?? reason` here.
        p.onInsert = { [weak self] text in
            guard let self else { return "电脑没有响应" }
            return self.insert(text)
        }
        p.onUnauthorized = { [weak self] in self?.signedOut(message: "登录已失效，请重新登录") }
        push = p
        p.start()
        loadMe()
        // Ask once after signing in; the menu keeps pointing at it.
        if fresh || !inserter.trusted { askForPermissionIfNeeded() }
    }

    private func loadMe() {
        guard let s = session else { return }
        Task {
            do {
                me = try await API.me(s)
                onChange()
            } catch let e as APIError where e.status == 401 {
                signedOut(message: "登录已失效，请重新登录")
            } catch {}
        }
    }

    private func signedOut(message: String?) {
        push?.stop()
        push = nil
        session = nil
        me = nil
        recent.clear()
        Keychain.delete()
        phase = .loggedOut(message: message)
        onChange()
    }

    /// Returns an error message if the server could not be told, in which
    /// case the Mac stays signed in.
    func signOut() async -> String? {
        guard let s = session else { return nil }
        do {
            try await API.signOut(s)
        } catch let e as APIError {
            return e.status == nil ? "离线时无法退出登录" : e.message
        } catch {
            return "退出登录失败"
        }
        signedOut(message: nil)
        return nil
    }

    func setServer(_ url: URL) {
        Settings.server = url
        onChange()
    }

    // MARK: Inserting

    private func insert(_ text: String) -> String? {
        recent.add(text)
        let error = inserter.insert(text)
        // The permission may have been revoked meanwhile.
        watchPermission()
        onChange()
        return error
    }

    func copyRecent(_ index: Int) {
        guard recent.items.indices.contains(index) else { return }
        inserter.copy(recent.items[index].text)
    }

    private func askForPermissionIfNeeded() {
        guard !inserter.trusted else { return }
        inserter.askForPermission()
        watchPermission()
    }

    func openPermissionSettings() {
        inserter.openPermissionSettings()
        watchPermission()
    }

    /// The system does not say when the permission is granted, so look
    /// every couple of seconds until it is.
    private func watchPermission() {
        updateTrust()
        guard permissionTimer == nil, !trusted else { return }
        permissionTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.updateTrust()
                if self.trusted {
                    self.permissionTimer?.invalidate()
                    self.permissionTimer = nil
                }
            }
        }
    }

    private func updateTrust() {
        let t = inserter.trusted
        if t != trusted {
            trusted = t
            onChange()
        }
    }

    // MARK: Launch at login

    var launchAtLogin: Bool { SMAppService.mainApp.status == .enabled }

    private func initLaunchAtLogin() {
        guard !Settings.launchAtLoginInitialized else { return }
        Settings.launchAtLoginInitialized = true
        try? SMAppService.mainApp.register()
    }

    func toggleLaunchAtLogin() {
        let app = SMAppService.mainApp
        do {
            if app.status == .enabled {
                try app.unregister()
            } else {
                try app.register()
            }
        } catch {}
        if app.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
        onChange()
    }

    // MARK: Updates

    /// Checks shortly after launch and then once a day; the app stays
    /// running for weeks. A failed check stays quiet.
    private func scheduleUpdateChecks() {
        guard isReleaseVersion(version) else { return }
        let check = { [weak self] in
            Task { @MainActor in await self?.checkForUpdate() }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { check() }
        Timer.scheduledTimer(withTimeInterval: 24 * 3600, repeats: true) { _ in check() }
    }

    private func checkForUpdate() async {
        guard let latest = try? await API.latestVersion() else { return }
        update = isNewer(latest, than: version) ? latest : nil
        onChange()
    }

    func openUpdate() { NSWorkspace.shared.open(latestReleasePage) }
}
