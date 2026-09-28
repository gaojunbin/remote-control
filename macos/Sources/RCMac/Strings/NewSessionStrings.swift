// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `newSession` group of the web's string table.
public struct NewSessionStrings: Sendable {
    public let title: String
    public let continuingOn: @Sendable (String) -> String
    public let pickDevice: String
    public let device: String
    public let agent: String
    public let agentUnavailable: String
    public let model: String
    public let effort: String
    public let permissions: String
    public let speed: String
    public let workingDirectory: String
    public let browse: String
    public let dirExists: String
    public let dirMissing: String
    public let dirChecking: String
    public let recent: String
    public let git: String
    public let gitClean: String
    public let gitDirty: String
    public let gitAhead: @Sendable (Int) -> String
    public let gitBehind: @Sendable (Int) -> String
    public let notARepo: String
    public let isolateWorktree: String
    public let start: String
    public let starting: String
    public let startFailed: String
    public let noDevices: String
    public let browseTitle: String
    public let browseUp: String
    public let browseUse: String
    public let browseEmpty: String
    /// A37: the picker makes one folder where the session will work.
    public let newFolder: String
    public let newFolderName: String
    public let newFolderCreate: String
    public let newFolderExists: String
}

extension NewSessionStrings {
    static let en = NewSessionStrings(
        title: "New session",
        continuingOn: { device in "Continuing on \(device)" },
        pickDevice: "Pick a device to start on",
        device: "Device",
        agent: "Agent",
        agentUnavailable: "not installed",
        model: "Model",
        effort: "Effort",
        permissions: "Permissions",
        speed: "Speed",
        workingDirectory: "Working directory",
        browse: "Browse…",
        dirExists: "exists",
        dirMissing: "not found",
        dirChecking: "checking…",
        recent: "Recent",
        git: "Git",
        gitClean: "clean",
        gitDirty: "uncommitted changes",
        gitAhead: { n in "\(n) ahead" },
        gitBehind: { n in "\(n) behind" },
        notARepo: "Not a git repository",
        isolateWorktree: "Isolate in worktree",
        start: "Start session",
        starting: "Starting…",
        startFailed: "Could not start the session.",
        noDevices: "No device is online.",
        browseTitle: "Choose a directory",
        browseUp: "Up one level",
        browseUse: "Use this directory",
        browseEmpty: "No subdirectories.",
        newFolder: "New folder",
        newFolderName: "Folder name",
        newFolderCreate: "Create",
        newFolderExists: "A folder with that name already exists."
    )

    static let zhHans = NewSessionStrings(
        title: "新建会话",
        continuingOn: { device in "在 \(device) 上继续" },
        pickDevice: "选择要启动的设备",
        device: "设备",
        agent: "Agent",
        agentUnavailable: "未安装",
        model: "模型",
        effort: "思考强度",
        permissions: "权限",
        speed: "速度",
        workingDirectory: "工作目录",
        browse: "浏览…",
        dirExists: "存在",
        dirMissing: "未找到",
        dirChecking: "检查中…",
        recent: "最近",
        git: "Git",
        gitClean: "干净",
        gitDirty: "有未提交的改动",
        gitAhead: { n in "领先 \(n)" },
        gitBehind: { n in "落后 \(n)" },
        notARepo: "不是 git 仓库",
        isolateWorktree: "在 worktree 中隔离",
        start: "开始会话",
        starting: "启动中…",
        startFailed: "无法启动会话。",
        noDevices: "没有在线的设备。",
        browseTitle: "选择目录",
        browseUp: "上一级",
        browseUse: "使用此目录",
        browseEmpty: "没有子目录。",
        newFolder: "新建文件夹",
        newFolderName: "文件夹名称",
        newFolderCreate: "创建",
        newFolderExists: "已存在同名文件夹。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> NewSessionStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
