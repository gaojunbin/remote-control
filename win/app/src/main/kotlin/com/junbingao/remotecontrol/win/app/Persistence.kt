package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.persistence.DraftStore
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.persistence.SecretStore
import com.junbingao.remotecontrol.core.state.MemoryUserDefaults
import com.junbingao.remotecontrol.core.state.UserDefaults
import com.junbingao.remotecontrol.win.platform.AppData
import com.junbingao.remotecontrol.win.platform.DpapiSecretVault
import com.junbingao.remotecontrol.win.platform.FileUserDefaults
import com.junbingao.remotecontrol.win.platform.Host
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where this run keeps what outlives a screen: the bearer token, the preferences, the cached lists
 * and transcripts, the drafts.
 *
 * An ordinary run keeps them where the app always does — sealed with DPAPI, in the defaults file
 * and the cache and drafts folders of the app's data folder (`AppData`), where the Mac keeps the
 * Keychain, the standard defaults and Application Support. `--ephemeral` keeps the token and the
 * defaults in memory and the files in a scratch directory that quitting removes, so nothing of the
 * person's is read or written. A run anywhere but Windows has no DPAPI and keeps the token in
 * memory.
 */
class Persistence(ephemeral: Boolean) {
    val defaults: UserDefaults
    val secrets: SecretStore
    val cache: LocalCache
    val drafts: DraftStore

    /** Where an ephemeral run keeps its cache and its drafts. */
    private val scratch: Path?

    init {
        if (ephemeral) {
            val scratch = Files.createTempDirectory("RemoteControl-")
            this.scratch = scratch
            defaults = MemoryUserDefaults()
            secrets = MemorySecretStore()
            cache = LocalCache(scratch.resolve("Cache").toFile())
            drafts = DraftStore(scratch.resolve("Drafts").toFile())
        } else {
            scratch = null
            defaults = FileUserDefaults(AppData.defaults)
            secrets = if (Host.isWindows) DpapiSecretVault(AppData.secrets) else MemorySecretStore()
            cache = LocalCache(AppData.cache.toFile())
            drafts = DraftStore(AppData.drafts.toFile())
        }
    }

    val isEphemeral: Boolean get() = scratch != null

    /**
     * Remove what an ephemeral run wrote: the scratch directory of its cache and drafts. Its
     * defaults and its token were only ever in memory. Called when the app quits, and by the
     * renderer and the tests after every model.
     */
    fun discard() {
        scratch?.toFile()?.deleteRecursively()
    }
}
