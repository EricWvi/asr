// swift-tools-version: 6.0
// The Mac client: a menu bar app that keeps the push connection and types
// what the Android client sends at the cursor. scripts/bundle.sh wraps the
// executable into TingXie.app.
import PackageDescription

let package = Package(
    name: "TingXie",
    platforms: [.macOS(.v13)],
    targets: [
        // Logic without AppKit, so it can be unit tested.
        .target(name: "TingXieCore"),
        .executableTarget(name: "TingXie", dependencies: ["TingXieCore"]),
        .testTarget(name: "TingXieCoreTests", dependencies: ["TingXieCore"]),
    ],
    swiftLanguageModes: [.v5]
)
