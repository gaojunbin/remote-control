// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RemoteControlIOS",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "RCCore", targets: ["RCCore"]),
        .library(name: "RCUI", targets: ["RCUI"]),
        .executable(name: "RCVerify", targets: ["RCVerify"]),
        .executable(name: "RCUIVerify", targets: ["RCUIVerify"]),
        .executable(name: "RCPreview", targets: ["RCPreview"])
    ],
    targets: [
        .target(name: "RCCore"),
        .target(name: "RCUI", dependencies: ["RCCore"], resources: [.copy("Resources/Markdown")]),
        .executableTarget(name: "RCVerify", dependencies: ["RCCore"], path: "Verification"),
        .executableTarget(name: "RCUIVerify", dependencies: ["RCUI", "RCCore"], path: "VerificationUI"),
        .executableTarget(name: "RCPreview", dependencies: ["RCUI"]),
        .testTarget(name: "RCCoreTests", dependencies: ["RCCore"])
    ]
)
