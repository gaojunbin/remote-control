package com.junbingao.remotecontrol.android.persistence

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.junbingao.remotecontrol.core.state.UserDefaults

/**
 * The core's [UserDefaults] on Android's `SharedPreferences`: the iPhone's standard defaults, a
 * small table of plain values that outlives a launch. Read as Foundation reads its own — a key
 * that holds nothing, or a value of another type, reads as null, false or zero — and written
 * through at once, as `UserDefaults` is by the time a launch could end.
 *
 * `SharedPreferences` keeps no doubles and no ordered lists: a double is kept as its bits, and a
 * list as a set, which is what every list the stores keep is (the folded device groups).
 */
class SharedPreferencesDefaults(private val storage: SharedPreferences) : UserDefaults {
    override fun string(forKey: String): String? = storage.all[forKey] as? String

    override fun stringArray(forKey: String): List<String>? =
        (storage.all[forKey] as? Set<*>)?.filterIsInstance<String>()?.sorted()

    override fun bool(forKey: String): Boolean = storage.all[forKey] as? Boolean ?: false

    override fun double(forKey: String): Double =
        (storage.all[forKey] as? Long)?.let(java.lang.Double::longBitsToDouble) ?: 0.0

    override fun set(value: String, forKey: String) = write { putString(forKey, value) }

    override fun set(value: Boolean, forKey: String) = write { putBoolean(forKey, value) }

    override fun set(value: Double, forKey: String) = write { putLong(forKey, java.lang.Double.doubleToRawLongBits(value)) }

    override fun set(value: List<String>, forKey: String) = write { putStringSet(forKey, value.toSet()) }

    override fun removeObject(forKey: String) = write { remove(forKey) }

    override val keys: Set<String> get() = storage.all.keys.toSet()

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        storage.edit().apply(change).commit()
    }

    companion object {
        /** The app's own table, private to it and kept out of backups with the rest of its data. */
        fun standard(context: Context): SharedPreferencesDefaults =
            SharedPreferencesDefaults(context.applicationContext.getSharedPreferences("defaults", Context.MODE_PRIVATE))
    }
}
