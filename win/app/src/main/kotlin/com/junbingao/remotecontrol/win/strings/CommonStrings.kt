// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** The `common` group of the web's string table. */
class CommonStrings(
    val cancel: String,
    val close: String,
    val `continue`: String,
    val copy: String,
    val copied: String,
    val retry: String,
    val remove: String,
    val save: String,
    val rename: String,
    val revoke: String,
    val search: String,
    val loading: String,
    val expand: String,
    val collapse: String,
    val showMore: String,
    val showLess: String,
    val dismiss: String,
    val unknown: String,
    val none: String,
    val ok: String,
    val done: String,
    val of: String,
) {
    companion object {
        val en = CommonStrings(
            cancel = "Cancel",
            close = "Close",
            `continue` = "Continue",
            copy = "Copy",
            copied = "Copied",
            retry = "Retry",
            remove = "Remove",
            save = "Save",
            rename = "Rename",
            revoke = "Revoke",
            search = "Search",
            loading = "Loading…",
            expand = "expand",
            collapse = "collapse",
            showMore = "Show more",
            showLess = "Show less",
            dismiss = "Dismiss",
            unknown = "Unknown",
            none = "None",
            ok = "OK",
            done = "Done",
            of = "of",
        )

        val zhHans = CommonStrings(
            cancel = "取消",
            close = "关闭",
            `continue` = "继续",
            copy = "复制",
            copied = "已复制",
            retry = "重试",
            remove = "移除",
            save = "保存",
            rename = "重命名",
            revoke = "吊销",
            search = "搜索",
            loading = "加载中…",
            expand = "展开",
            collapse = "收起",
            showMore = "显示更多",
            showLess = "收起",
            dismiss = "忽略",
            unknown = "未知",
            none = "无",
            ok = "确定",
            done = "完成",
            of = "/",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): CommonStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
