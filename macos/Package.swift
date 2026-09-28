// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RemoteControlMac",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "RCMac", targets: ["RCMac"]),
        .executable(name: "RCMacPreview", targets: ["RCMacPreview"])
    ],
    // The protocol, the transport and the stores are the iPhone app's
    // (`ios/Sources/RCCore`); only RCCore is taken from that package, never
    // RCUI, because the Mac app draws the web app and not the iPhone's screens.
    //
    // SwiftTerm is pinned to the same release as `ios/Package.swift`, for the
    // same reason: every release from 1.12.0 carries a Metal shader that Xcode 26
    // compiles with a toolchain neither this machine nor a GitHub runner has.
    // Only the terminal page imports it.
    dependencies: [
        .package(path: "../ios"),
        .package(url: "https://github.com/migueldeicaza/SwiftTerm", exact: "1.11.2")
    ],
    targets: [
        .target(name: "RCMac",
                dependencies: [
                    .product(name: "RCCore", package: "ios"),
                    .product(name: "SwiftTerm", package: "SwiftTerm")
                ],
                exclude: ["Design/Icons/LICENSE-lucide.txt"],
                resources: [.process("Resources/Assets.xcassets"), .copy("Resources/Highlight")]),
        .executableTarget(name: "RCMacPreview", dependencies: ["RCMac"]),
        .testTarget(name: "RCMacTests", dependencies: ["RCMac"])
    ]
)
