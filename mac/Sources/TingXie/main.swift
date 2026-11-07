import AppKit

/// A menu bar app: no Dock icon, no main window (LSUIElement in Info.plist).
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let model = AppModel()
    private var menu: MenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        menu = MenuController(model: model)
        model.start()
    }
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = AppDelegate()
    app.delegate = delegate
    app.setActivationPolicy(.accessory)
    app.run()
}
