import AppKit

/// A menu bar app: no Dock icon, no main window (LSUIElement in Info.plist).
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let model = AppModel()
    private var menu: MenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        menu = MenuController(model: model)
        model.start()
        // Development only: TINGXIE_DEMO=1 fills the recent inserts and opens
        // the menu, so its layout can be screenshotted.
        if ProcessInfo.processInfo.environment["TINGXIE_DEMO"] == "1" {
            model.seedDemo()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.menu?.open() }
        }
    }
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = AppDelegate()
    app.delegate = delegate
    app.setActivationPolicy(.accessory)
    app.run()
}
