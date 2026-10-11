// swift-tools-version: 6.2
// CaromKit: the reusable core of the Carom macOS app (protocol, Hub client, storage, sync engine).
// Apple frameworks only (CryptoKit, Foundation, Security, Network, plus AppKit, SwiftUI, UserNotifications, Intents, Carbon and ServiceManagement in CaromDesktop); no third-party dependencies.

import PackageDescription

let strict: [SwiftSetting] = [
    .swiftLanguageMode(.v6),
    .treatAllWarnings(as: .error),
]

let package = Package(
    name: "CaromKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "CaromProtocol", targets: ["CaromProtocol"]),
        .library(name: "CaromHub", targets: ["CaromHub"]),
        .library(name: "CaromStore", targets: ["CaromStore"]),
        .library(name: "CaromSync", targets: ["CaromSync"]),
        .library(name: "CaromDesktop", targets: ["CaromDesktop"]),
    ],
    targets: [
        .target(name: "CaromProtocol", swiftSettings: strict),
        .target(name: "CaromHub", dependencies: ["CaromProtocol"], swiftSettings: strict),
        .target(name: "CaromStore", dependencies: ["CaromProtocol"], swiftSettings: strict),
        .target(
            name: "CaromSync",
            dependencies: ["CaromProtocol", "CaromHub", "CaromStore"],
            swiftSettings: strict
        ),
        // macOS-only services: notifications (native and the rich panel), pasteboard, global hotkey, login item.
        .target(name: "CaromDesktop", dependencies: ["CaromProtocol"], swiftSettings: strict),
        .testTarget(name: "CaromDesktopTests", dependencies: ["CaromDesktop", "CaromProtocol"], swiftSettings: strict),
        .testTarget(name: "CaromProtocolTests", dependencies: ["CaromProtocol"], swiftSettings: strict),
        .testTarget(name: "CaromHubTests", dependencies: ["CaromHub"], swiftSettings: strict),
        .testTarget(name: "CaromStoreTests", dependencies: ["CaromStore"], swiftSettings: strict),
        .testTarget(
            name: "CaromSyncTests",
            dependencies: ["CaromSync", "CaromHub", "CaromStore", "CaromProtocol"],
            swiftSettings: strict
        ),
    ]
)
