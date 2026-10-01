package com.junbingao.remotecontrol.android.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * How loud the microphone is, on the 0…1 scale the waveform and the listening glow read.
 *
 * Loudness is logarithmic, so the root-mean-square amplitude of a buffer is mapped through
 * decibels. Where the ends of that scale sit decides how much of the glow's swing ordinary speech
 * actually uses: a room with nobody talking sits around −50 dBFS on a phone's microphone and
 * conversational speech peaks near −12, so those are the ends (`InputLevel.swift`).
 *
 * Both backends map it here so the two cannot drift apart.
 */
object InputLevel {
    /** A quiet room. */
    const val floorDB = -50.0

    /** Conversational speech at arm's length, where the meter is full. */
    const val ceilingDB = -12.0

    fun from(rms: Double): Double {
        val decibels = 20 * log10(max(rms, 0.0001))
        return min(1.0, max(0.0, (decibels - floorDB) / (ceilingDB - floorDB)))
    }
}
