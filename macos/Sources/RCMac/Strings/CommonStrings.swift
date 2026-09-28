// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `common` group of the web's string table.
public struct CommonStrings: Sendable {
    public let cancel: String
    public let close: String
    public let `continue`: String
    public let copy: String
    public let copied: String
    public let retry: String
    public let remove: String
    public let save: String
    public let rename: String
    public let revoke: String
    public let search: String
    public let loading: String
    public let expand: String
    public let collapse: String
    public let showMore: String
    public let showLess: String
    public let dismiss: String
    public let unknown: String
    public let none: String
    public let ok: String
    public let done: String
    public let of: String
}

extension CommonStrings {
    static let en = CommonStrings(
        cancel: "Cancel",
        close: "Close",
        continue: "Continue",
        copy: "Copy",
        copied: "Copied",
        retry: "Retry",
        remove: "Remove",
        save: "Save",
        rename: "Rename",
        revoke: "Revoke",
        search: "Search",
        loading: "Loading…",
        expand: "expand",
        collapse: "collapse",
        showMore: "Show more",
        showLess: "Show less",
        dismiss: "Dismiss",
        unknown: "Unknown",
        none: "None",
        ok: "OK",
        done: "Done",
        of: "of"
    )

    static let zhHans = CommonStrings(
        cancel: "取消",
        close: "关闭",
        continue: "继续",
        copy: "复制",
        copied: "已复制",
        retry: "重试",
        remove: "移除",
        save: "保存",
        rename: "重命名",
        revoke: "吊销",
        search: "搜索",
        loading: "加载中…",
        expand: "展开",
        collapse: "收起",
        showMore: "显示更多",
        showLess: "收起",
        dismiss: "忽略",
        unknown: "未知",
        none: "无",
        ok: "确定",
        done: "完成",
        of: "/"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> CommonStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
