// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RemoteControlIOS",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "RCCore", targets: ["RCCore"]),
        .library(name: "RCUI", targets: ["RCUI"]),
        .executable(name: "RCVerify", targets: ["RCVerify"]),
        .executable(name: "RCUIVerify", targets: ["RCUIVerify"]),
        .executable(name: "RCPreview", targets: ["RCPreview"])
    ],
    // Amendment A38: the terminal emulator, and the only third-party code in
    // the app. It is pinned exactly — a terminal that changes how it renders
    // between builds is not something to discover on a phone — and it is built
    // for iOS alone: `TerminalHost` is the one file that imports it, the checks
    // and the tests run on macOS, and neither needs it there.
    //
    // 1.11.2 and not the newest: every release from 1.12.0 carries a Metal
    // shader, which Xcode 26 compiles with a toolchain that is a separate
    // multi-gigabyte download and is on neither this machine nor a GitHub
    // runner. SwiftTerm's Metal renderer is off by default, so the newer
    // releases would cost a build dependency for a path the app never takes.
    dependencies: [
        .package(url: "https://github.com/migueldeicaza/SwiftTerm", exact: "1.11.2")
    ],
    targets: [
        .target(name: "RCCore"),
        .target(name: "RCUI",
                dependencies: [
                    "RCCore",
                    .product(name: "SwiftTerm", package: "SwiftTerm", condition: .when(platforms: [.iOS]))
                ],
                resources: [.copy("Resources/Markdown"), .process("Resources/Agents.xcassets")]),
        .executableTarget(name: "RCVerify", dependencies: ["RCCore"], path: "Verification"),
        .executableTarget(name: "RCUIVerify", dependencies: ["RCUI", "RCCore"], path: "VerificationUI"),
        .executableTarget(name: "RCPreview", dependencies: ["RCUI"]),
        .testTarget(name: "RCCoreTests", dependencies: ["RCCore"])
    ]
)
