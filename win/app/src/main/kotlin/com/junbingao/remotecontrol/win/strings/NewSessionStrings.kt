// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** The `newSession` group of the web's string table. */
class NewSessionStrings(
    val title: String,
    val continuingOn: (String) -> String,
    val pickDevice: String,
    val device: String,
    val agent: String,
    val agentUnavailable: String,
    val model: String,
    val effort: String,
    val permissions: String,
    val speed: String,
    val workingDirectory: String,
    val browse: String,
    val dirExists: String,
    val dirMissing: String,
    val dirChecking: String,
    val recent: String,
    val git: String,
    val gitClean: String,
    val gitDirty: String,
    val gitAhead: (Int) -> String,
    val gitBehind: (Int) -> String,
    val notARepo: String,
    val isolateWorktree: String,
    val start: String,
    val starting: String,
    val startFailed: String,
    val noDevices: String,
    val browseTitle: String,
    val browseUp: String,
    val browseUse: String,
    val browseEmpty: String,
    /** A37: the picker makes one folder where the session will work. */
    val newFolder: String,
    val newFolderName: String,
    val newFolderCreate: String,
    val newFolderExists: String,
) {
    companion object {
        val en = NewSessionStrings(
            title = "New session",
            continuingOn = { device -> "Continuing on $device" },
            pickDevice = "Pick a device to start on",
            device = "Device",
            agent = "Agent",
            agentUnavailable = "not installed",
            model = "Model",
            effort = "Effort",
            permissions = "Permissions",
            speed = "Speed",
            workingDirectory = "Working directory",
            browse = "Browse…",
            dirExists = "exists",
            dirMissing = "not found",
            dirChecking = "checking…",
            recent = "Recent",
            git = "Git",
            gitClean = "clean",
            gitDirty = "uncommitted changes",
            gitAhead = { n -> "$n ahead" },
            gitBehind = { n -> "$n behind" },
            notARepo = "Not a git repository",
            isolateWorktree = "Isolate in worktree",
            start = "Start session",
            starting = "Starting…",
            startFailed = "Could not start the session.",
            noDevices = "No device is online.",
            browseTitle = "Choose a directory",
            browseUp = "Up one level",
            browseUse = "Use this directory",
            browseEmpty = "No subdirectories.",
            newFolder = "New folder",
            newFolderName = "Folder name",
            newFolderCreate = "Create",
            newFolderExists = "A folder with that name already exists.",
        )

        val zhHans = NewSessionStrings(
            title = "新建会话",
            continuingOn = { device -> "在 $device 上继续" },
            pickDevice = "选择要启动的设备",
            device = "设备",
            agent = "Agent",
            agentUnavailable = "未安装",
            model = "模型",
            effort = "思考强度",
            permissions = "权限",
            speed = "速度",
            workingDirectory = "工作目录",
            browse = "浏览…",
            dirExists = "存在",
            dirMissing = "未找到",
            dirChecking = "检查中…",
            recent = "最近",
            git = "Git",
            gitClean = "干净",
            gitDirty = "有未提交的改动",
            gitAhead = { n -> "领先 $n" },
            gitBehind = { n -> "落后 $n" },
            notARepo = "不是 git 仓库",
            isolateWorktree = "在 worktree 中隔离",
            start = "开始会话",
            starting = "启动中…",
            startFailed = "无法启动会话。",
            noDevices = "没有在线的设备。",
            browseTitle = "选择目录",
            browseUp = "上一级",
            browseUse = "使用此目录",
            browseEmpty = "没有子目录。",
            newFolder = "新建文件夹",
            newFolderName = "文件夹名称",
            newFolderCreate = "创建",
            newFolderExists = "已存在同名文件夹。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): NewSessionStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
