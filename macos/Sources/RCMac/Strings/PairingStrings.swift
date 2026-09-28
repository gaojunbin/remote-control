// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `pairing` group of the web's string table.
public struct PairingStrings: Sendable {
    public let title: String
    public let intro: String
    public let singleUse: String
    public let expiresIn: @Sendable (String) -> String
    public let expired: String
    public let newCode: String
    public let waiting: String
    public let listening: @Sendable (String) -> String
    public let stepGateway: String
    public let stepHandshake: String
    public let stepAgents: String
    public let connected: @Sendable (String) -> String
    public let noCurl: String
    public let manualInstall: String
    public let manualTitle: String
    public let manualSteps: [String]
    public let manualPairCommand: @Sendable (String, String) -> String
    public let createFailed: String
    public let scanTitle: String
    public let scanBody: String
    public let scanCommand: @Sendable (String) -> String
    public let claimTitle: String
    public let claimIntro: String
    public let claiming: String
    public let claimInvalid: String
    public let claimExpired: String
    public let claimUsed: String
    public let claimFailed: String
}

extension PairingStrings {
    static let en = PairingStrings(
        title: "Add device",
        intro: "Run one command on the machine where your agents live. It dials out to the gateway — nothing is exposed on the host.",
        singleUse: "single use",
        expiresIn: { clock in "expires in \(clock)" },
        expired: "expired",
        newCode: "New code",
        waiting: "Waiting for this device",
        listening: { clock in "listening \(clock)" },
        stepGateway: "Gateway ready",
        stepHandshake: "Device handshake",
        stepAgents: "Detect installed agents",
        connected: { name in "\(name) connected" },
        noCurl: "No curl on the host?",
        manualInstall: "Manual install",
        manualTitle: "Manual install",
        manualSteps: [
            "Install the client: python3 -m pip install --user rc-client",
            "Pair it with this gateway using the code below.",
            "The client keeps running in the background and dials out only."
        ],
        manualPairCommand: { origin, code in "rc-client pair --gateway \(origin) --code \(code)" },
        createFailed: "Could not create a pairing code.",
        scanTitle: "From your phone",
        scanBody: "The host prints a QR code. Scan it with the phone app, or open its link here.",
        scanCommand: { origin in "curl -fsSL \(origin)/install.sh | sh" },
        claimTitle: "Pair this host",
        claimIntro: "The host that printed this code is joining your gateway.",
        claiming: "Claiming this code…",
        claimInvalid: "This link carries no code.",
        claimExpired: "This code has expired. Run the command again on the host.",
        claimUsed: "This code was already used.",
        claimFailed: "Could not claim this code."
    )

    static let zhHans = PairingStrings(
        title: "添加设备",
        intro: "在运行 agent 的机器上执行一条命令。它主动外连网关，主机不对外暴露任何端口。",
        singleUse: "一次性",
        expiresIn: { clock in "\(clock) 后过期" },
        expired: "已过期",
        newCode: "新配对码",
        waiting: "等待此设备",
        listening: { clock in "监听中 \(clock)" },
        stepGateway: "网关就绪",
        stepHandshake: "设备握手",
        stepAgents: "检测已安装的 agent",
        connected: { name in "\(name) 已连接" },
        noCurl: "主机上没有 curl？",
        manualInstall: "手动安装",
        manualTitle: "手动安装",
        manualSteps: [
            "安装客户端：python3 -m pip install --user rc-client",
            "用下面的配对码与本网关配对。",
            "客户端在后台常驻，只主动外连。"
        ],
        manualPairCommand: { origin, code in "rc-client pair --gateway \(origin) --code \(code)" },
        createFailed: "无法创建配对码。",
        scanTitle: "从手机添加",
        scanBody: "主机会打印一个二维码。用手机 App 扫描它，或在这里打开它的链接。",
        scanCommand: { origin in "curl -fsSL \(origin)/install.sh | sh" },
        claimTitle: "配对这台主机",
        claimIntro: "打印出这个码的主机正在加入你的网关。",
        claiming: "正在认领此码…",
        claimInvalid: "此链接不含配对码。",
        claimExpired: "此码已过期。请在主机上重新执行该命令。",
        claimUsed: "此码已被使用。",
        claimFailed: "无法认领此码。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> PairingStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
