package com.junbingao.remotecontrol.android.persistence

import android.content.Context
import java.io.File

/**
 * Where the core keeps what it writes: RCCore's Application Support `RemoteControl/Drafts` and
 * `RemoteControl/Cache`, here under the app's no-backup directory. The iPhone keeps its cache out
 * of backups (`LocalCache` marks it so); here the drafts stay out too, as the whole app does.
 */
object AppDirectories {
    fun drafts(context: Context): File = root(context).resolve("Drafts")

    fun cache(context: Context): File = root(context).resolve("Cache")

    private fun root(context: Context): File = context.noBackupFilesDir.resolve("RemoteControl")
}
