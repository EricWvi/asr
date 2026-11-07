import AppKit

/// System alerts. A menu bar app has no window of its own, so each alert
/// brings the app to the front first.
@MainActor
enum Dialogs {
    /// The server address dialog; nil when cancelled.
    static func server(_ text: String, error: String?) -> String? {
        let alert = NSAlert()
        alert.messageText = "服务器地址"
        alert.informativeText = "局域网开发服务可以填 http://IP:端口"
        alert.addButton(withTitle: "保存")
        alert.addButton(withTitle: "取消")

        let field = NSTextField(string: text)
        field.frame = NSRect(x: 0, y: error == nil ? 0 : 20, width: 260, height: 24)
        let box = NSView(frame: NSRect(x: 0, y: 0, width: 260, height: error == nil ? 24 : 44))
        box.addSubview(field)
        if let error {
            let label = NSTextField(labelWithString: error)
            label.textColor = .systemRed
            label.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
            label.alignment = .center
            label.frame = NSRect(x: 0, y: 0, width: 260, height: 16)
            box.addSubview(label)
        }
        alert.accessoryView = box
        alert.window.initialFirstResponder = field
        NSApp.activate(ignoringOtherApps: true)
        return alert.runModal() == .alertFirstButtonReturn ? field.stringValue : nil
    }

    static func confirmSignOut() -> Bool {
        let alert = NSAlert()
        alert.messageText = "退出登录？"
        alert.informativeText = "退出后这台 Mac 不再接收安卓端发送的文字。"
        alert.addButton(withTitle: "退出登录")
        alert.addButton(withTitle: "取消")
        NSApp.activate(ignoringOtherApps: true)
        return alert.runModal() == .alertFirstButtonReturn
    }

    static func notice(_ title: String, _ message: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

}
