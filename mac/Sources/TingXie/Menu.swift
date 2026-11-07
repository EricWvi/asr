import AppKit
import TingXieCore

/// The status item and its menu, after design/mac.html.
@MainActor
final class MenuController: NSObject, NSMenuDelegate {
    private let model: AppModel
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private let badge = CALayer()

    init(model: AppModel) {
        self.model = model
        super.init()
        menu.autoenablesItems = false
        menu.delegate = self
        item.menu = menu
        if let button = item.button {
            let image = NSImage(named: "TrayTemplate")
            image?.isTemplate = true
            image?.accessibilityDescription = "听·写"
            button.image = image
            // A small orange dot when the Accessibility permission is missing.
            button.wantsLayer = true
            badge.backgroundColor = NSColor.systemOrange.cgColor
            badge.cornerRadius = 3
            badge.isHidden = true
            button.layer?.addSublayer(badge)
        }
        model.onChange = { [weak self] in self?.refresh() }
    }

    func refresh() {
        guard let button = item.button else { return }
        let signedIn = model.session != nil
        button.appearsDisabled = !(signedIn && model.phase == .connected)
        badge.isHidden = !(signedIn && !model.trusted)
        let b = button.bounds
        badge.frame = CGRect(x: b.midX + 5, y: b.maxY - 9, width: 6, height: 6)
        build()
    }

    func menuNeedsUpdate(_ menu: NSMenu) { build() }

    // MARK: Building

    private func build() {
        menu.removeAllItems()
        if model.session == nil { buildSignedOut() } else { buildSignedIn() }
    }

    private func buildSignedOut() {
        var message = "未登录"
        if case let .loggedOut(m?) = model.phase { message = m }
        add(info(message))
        menu.addItem(.separator())
        if model.phase == .loggingIn {
            add(info("正在浏览器中登录…"))
            add(action("取消登录") { $0.model.cancelSignIn() })
        } else {
            add(action("登录…") { $0.model.signIn() })
            add(action("服务器：\(serverLabel(model.server))…") { $0.editServer() })
        }
        menu.addItem(.separator())
        addLaunchAtLogin()
        menu.addItem(.separator())
        addTail()
    }

    private func buildSignedIn() {
        let (label, color): (String, NSColor) = switch model.phase {
        case .connected: ("已连接", NSColor(name: nil) { $0.isDark ? NSColor(srgbRed: 0.55, green: 0.85, blue: 0.58, alpha: 1) : NSColor(srgbRed: 0.27, green: 0.6, blue: 0.32, alpha: 1) })
        case .offline: ("连不上服务器，正在重试", .systemOrange)
        default: ("正在连接…", .tertiaryLabelColor)
        }
        let status = info(label)
        status.image = dot(color)
        add(status)
        add(info("这台 Mac：\(model.session!.deviceName)"))
        menu.addItem(.separator())

        if !model.trusted {
            let warn = action("开启辅助功能权限才能插入文字…") { $0.model.openPermissionSettings() }
            let symbol = NSImage(systemSymbolName: "exclamationmark.circle", accessibilityDescription: nil)
            warn.image = symbol?.withSymbolConfiguration(.init(paletteColors: [.systemOrange]))
            add(warn)
            menu.addItem(.separator())
        }

        add(header("最近插入（点击复制）"))
        if model.recent.items.isEmpty {
            add(info("还没有收到文字"))
        } else {
            for (i, r) in model.recent.items.enumerated() {
                let it = action("") { $0.model.copyRecent(i) }
                it.attributedTitle = withTime(menuLine(r.text), menuTime(r.at))
                it.toolTip = r.text
                add(it)
            }
        }
        menu.addItem(.separator())
        addLaunchAtLogin()
        menu.addItem(.separator())
        if let me = model.me { add(info("\(me.name)（\(me.email)）")) }
        add(action("退出登录…") { $0.confirmSignOut() })
        menu.addItem(.separator())
        addTail()
    }

    private func addLaunchAtLogin() {
        let it = action("开机时启动") { $0.model.toggleLaunchAtLogin() }
        it.state = model.launchAtLogin ? .on : .off
        add(it)
    }

    private func addTail() {
        if let v = model.update { add(action("有新版本 \(v)…") { $0.model.openUpdate() }) }
        let quit = NSMenuItem(title: "退出听·写", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        add(quit)
    }

    // MARK: Items

    private func add(_ item: NSMenuItem) { menu.addItem(item) }

    private func info(_ title: String) -> NSMenuItem {
        let it = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        it.isEnabled = false
        return it
    }

    private func header(_ title: String) -> NSMenuItem {
        if #available(macOS 14, *) { return .sectionHeader(title: title) }
        return info(title)
    }

    private func action(_ title: String, _ run: @escaping @MainActor (MenuController) -> Void) -> NSMenuItem {
        let it = ClosureItem(title: title, action: #selector(ClosureItem.fire), keyEquivalent: "")
        it.target = it
        it.run = { [weak self] in if let self { run(self) } }
        return it
    }

    /// The text, then the time aligned right in the secondary colour.
    private func withTime(_ text: String, _ time: String) -> NSAttributedString {
        let font = NSFont.menuFont(ofSize: 0)
        let style = NSMutableParagraphStyle()
        style.tabStops = [NSTextTab(textAlignment: .right, location: 300)]
        let s = NSMutableAttributedString(string: text + "\t", attributes: [.font: font, .paragraphStyle: style])
        s.append(NSAttributedString(string: time, attributes: [.font: font, .paragraphStyle: style, .foregroundColor: NSColor.secondaryLabelColor]))
        return s
    }

    private func dot(_ color: NSColor) -> NSImage {
        NSImage(size: NSSize(width: 8, height: 8), flipped: false) { r in
            color.setFill()
            NSBezierPath(ovalIn: r).fill()
            return true
        }
    }

    // MARK: Dialogs

    /// Saves the address only if it answers like this server; otherwise
    /// the dialog comes back with the reason.
    private func editServer(_ text: String? = nil, error: String? = nil) {
        guard let input = Dialogs.server(text ?? model.server.absoluteString, error: error) else { return }
        guard let url = normalizeServer(input) else { return editServer(input, error: "地址格式不对") }
        Task {
            if await API.reachable(url) {
                model.setServer(url)
            } else {
                editServer(input, error: "连不上这个服务器")
            }
        }
    }

    private func confirmSignOut() {
        guard Dialogs.confirmSignOut() else { return }
        Task {
            if let error = await model.signOut() { Dialogs.notice("无法退出登录", error) }
        }
    }
}

/// A menu item that runs a closure.
private final class ClosureItem: NSMenuItem {
    var run: (@MainActor () -> Void)?
    @objc func fire() { MainActor.assumeIsolated { run?() } }
}

extension NSAppearance {
    var isDark: Bool { bestMatch(from: [.darkAqua, .aqua]) == .darkAqua }
}
