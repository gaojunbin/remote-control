package com.junbingao.remotecontrol.core.state

/**
 * Foundation's `UserDefaults`, as RCCore's stores use it: a small table of plain values that
 * outlives a launch. The bearer token is never here — that is the `SecretStore`'s.
 *
 * A platform seam: each app brings its own (`SharedPreferences` on Android, the user's profile on
 * Windows), and [MemoryUserDefaults] is the one for tests and explicitly ephemeral runs. The
 * readers answer as Foundation's do — a key that holds nothing, or a value of another type, reads
 * as null, false or zero.
 */
interface UserDefaults {
    fun string(forKey: String): String?
    fun stringArray(forKey: String): List<String>?
    fun bool(forKey: String): Boolean
    fun double(forKey: String): Double
    fun set(value: String, forKey: String)
    fun set(value: Boolean, forKey: String)
    fun set(value: Double, forKey: String)
    fun set(value: List<String>, forKey: String)
    fun removeObject(forKey: String)

    /** Every key that holds a value, `dictionaryRepresentation().keys`: a copy, so a caller can remove keys while it reads them. */
    val keys: Set<String>
}

/** Defaults that live as long as the object: for tests and explicitly ephemeral runs. Never writes to disk. */
class MemoryUserDefaults : UserDefaults {
    private val values = LinkedHashMap<String, Any>()

    override fun string(forKey: String): String? = synchronized(values) { values[forKey] as? String }

    override fun stringArray(forKey: String): List<String>? =
        synchronized(values) { (values[forKey] as? List<*>)?.filterIsInstance<String>() }

    override fun bool(forKey: String): Boolean = synchronized(values) { values[forKey] as? Boolean ?: false }

    override fun double(forKey: String): Double = synchronized(values) { values[forKey] as? Double ?: 0.0 }

    override fun set(value: String, forKey: String) = store(value, forKey)

    override fun set(value: Boolean, forKey: String) = store(value, forKey)

    override fun set(value: Double, forKey: String) = store(value, forKey)

    override fun set(value: List<String>, forKey: String) = store(value.toList(), forKey)

    override fun removeObject(forKey: String) {
        synchronized(values) { values.remove(forKey) }
    }

    override val keys: Set<String> get() = synchronized(values) { values.keys.toSet() }

    private fun store(value: Any, key: String) {
        synchronized(values) { values[key] = value }
    }
}
