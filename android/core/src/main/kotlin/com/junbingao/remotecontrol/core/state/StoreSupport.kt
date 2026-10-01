package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// The parts of Swift the stores lean on that Kotlin spells differently: a property observer, the
// cancellation check, and an error's sentence.

/**
 * A store property with Swift's `didSet`: snapshot state, so a screen reading it is redrawn when
 * it changes, and [didSet] told of every assignment after it lands. The initial value is not an
 * assignment, as a Swift initializer's is not.
 */
internal class ObservedValue<T>(initial: T, private val didSet: (T) -> Unit) : ReadWriteProperty<Any?, T> {
    private val state = mutableStateOf(initial)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        didSet(value)
    }
}

internal fun <T> observedValue(initial: T, didSet: (T) -> Unit): ObservedValue<T> = ObservedValue(initial, didSet)

/** `Task.isCancelled`: whether the coroutine running this has been told to stop. */
internal suspend fun isCancelled(): Boolean = !currentCoroutineContext().isActive

/** `Error.localizedDescription`: the error's own sentence, which every RCCore error sets to its localized one. */
internal val Throwable.localizedDescription: String get() = message ?: toString()
