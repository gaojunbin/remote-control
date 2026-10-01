package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.shared.AttachmentLimits
import java.util.WeakHashMap

/**
 * The files of every session's draft, keyed by `<device>/<session>` — the half of
 * `web/src/stores/drafts.ts` the core does not hold. The words are the `ChatStore`'s `draft`; the
 * files are here, so opening another conversation shows its own and a return finds them again
 * (`docs/DESIGN.md` § "The composer" → **A draft belongs to its session**).
 *
 * A43: while a queued message is being edited, the files the field held wait aside and come back
 * when the edit ends, the way the core's `QueuedEdit` keeps the words aside. In memory for the
 * app's life, and emptied on sign-out.
 */
class ComposerDrafts {
    private var files: Map<String, List<ComposerAttachment>> by mutableStateOf(emptyMap())
    private var aside: Map<String, List<ComposerAttachment>> by mutableStateOf(emptyMap())

    fun attachments(key: String): List<ComposerAttachment> = files[key] ?: emptyList()

    /**
     * Append files, never past the contract's cap. Two attach operations can be in flight at once —
     * a paste and the file dialog — and each counted its budget before it began, so the cap is
     * applied here, where the list is written. Returns how many had to be dropped.
     */
    fun add(new: List<ComposerAttachment>, to: String): Int {
        if (new.isEmpty()) return 0
        val current = attachments(to)
        val dropped = maxOf(0, current.size + new.size - AttachmentLimits.maxAttachments)
        files = files + (to to (current + new).take(AttachmentLimits.maxAttachments))
        return dropped
    }

    fun remove(at: Int, from: String) {
        val current = attachments(from).toMutableList()
        if (at !in current.indices) return
        current.removeAt(at)
        files = if (current.isEmpty()) files - from else files + (from to current)
    }

    /** Hand back the files a refused send took, unless newer ones are there. */
    fun restore(old: List<ComposerAttachment>, to: String) {
        if (attachments(to).isNotEmpty() || old.isEmpty()) return
        files = files + (to to old)
    }

    fun clear(key: String) {
        files = files - key
    }

    /** A43: the field takes a queued message, and its files wait aside. */
    fun setAside(key: String) {
        aside = aside + (key to attachments(key))
        files = files - key
    }

    /** A43: the words are back in the line, and the files come back with the draft that waited aside. */
    fun endEdit(key: String) {
        val back = aside[key] ?: emptyList()
        aside = aside - key
        files = if (back.isEmpty()) files - key else files + (key to back)
    }

    fun reset() {
        files = emptyMap()
        aside = emptyMap()
    }

    companion object {
        /**
         * One store per app model: the app has one; a preview render makes one of its own for each
         * picture, and a store outlives none of them.
         */
        private val stores = WeakHashMap<WinAppModel, ComposerDrafts>()

        fun of(model: WinAppModel): ComposerDrafts = synchronized(stores) { stores.getOrPut(model) { ComposerDrafts() } }
    }
}
