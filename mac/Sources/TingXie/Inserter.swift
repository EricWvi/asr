import AppKit
import ApplicationServices

/// Types text at the cursor of whatever app has focus: the text goes on
/// the pasteboard, a ⌘V is posted, and the old pasteboard comes back a
/// moment later. Pasting works in nearly every app, native or not, and
/// keeps the text intact where typing it key by key would go through the
/// input method.
@MainActor
final class Inserter {
    /// What was on the pasteboard before the first of a burst of inserts.
    private var saved: [NSPasteboardItem]?
    private var restore: DispatchWorkItem?

    /// Posting key events needs the Accessibility permission.
    var trusted: Bool { AXIsProcessTrusted() }

    /// Shows the system prompt that leads to the Accessibility settings.
    func askForPermission() {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
    }

    func openPermissionSettings() {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    /// Returns the reason in Chinese if the text was not inserted.
    func insert(_ text: String) -> String? {
        guard trusted else {
            // Still useful: the text is one ⌘V away.
            copy(text)
            return "电脑没有开启辅助功能权限，文字已复制到剪贴板"
        }
        let pb = NSPasteboard.general
        if saved == nil { saved = Self.snapshot(pb) }
        pb.clearContents()
        let item = NSPasteboardItem()
        item.setString(text, forType: .string)
        // Tells clipboard managers to skip it (nspasteboard.org).
        item.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        pb.writeObjects([item])
        let ours = pb.changeCount
        postPaste()

        // The target app reads the pasteboard when it handles ⌘V, which may
        // take a moment; restore only if nobody has copied since.
        restore?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if pb.changeCount == ours, let saved = self.saved {
                pb.clearContents()
                if !saved.isEmpty { pb.writeObjects(saved) }
            }
            self.saved = nil
        }
        restore = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
        return nil
    }

    func copy(_ text: String) {
        let pb = NSPasteboard.general
        restore?.cancel()
        saved = nil
        pb.clearContents()
        pb.setString(text, forType: .string)
    }

    private func postPaste() {
        let src = CGEventSource(stateID: .combinedSessionState)
        // Keys the user happens to hold must not turn ⌘V into something else.
        src?.setLocalEventsFilterDuringSuppressionState([.permitLocalMouseEvents, .permitSystemDefinedEvents], state: .eventSuppressionStateSuppressionInterval)
        let v: CGKeyCode = 9
        let down = CGEvent(keyboardEventSource: src, virtualKey: v, keyDown: true)
        let up = CGEvent(keyboardEventSource: src, virtualKey: v, keyDown: false)
        down?.flags = .maskCommand
        up?.flags = .maskCommand
        down?.post(tap: .cgSessionEventTap)
        up?.post(tap: .cgSessionEventTap)
    }

    private static func snapshot(_ pb: NSPasteboard) -> [NSPasteboardItem] {
        (pb.pasteboardItems ?? []).map { item in
            let copy = NSPasteboardItem()
            for type in item.types {
                if let data = item.data(forType: type) { copy.setData(data, forType: type) }
            }
            return copy
        }
    }
}
