package com.junbingao.remotecontrol.win.platform

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * The web's `prefers-reduced-motion`, as Chrome reads it on Windows: "Animation effects" off in
 * Windows Settings (`SPI_GETCLIENTAREAANIMATION`). Anywhere else, and if Windows cannot be asked,
 * motion is on.
 */
object ReduceMotion {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    @Suppress("FunctionName")
    private interface SystemParameters : StdCallLibrary {
        fun SystemParametersInfo(uiAction: Int, uiParam: Int, pvParam: IntByReference, fWinIni: Int): Boolean
    }

    val current: Boolean by lazy {
        if (!Host.isWindows) return@lazy false
        runCatching {
            val user32 = Native.load("user32", SystemParameters::class.java, W32APIOptions.DEFAULT_OPTIONS)
            val animates = IntByReference()
            user32.SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, animates, 0) && animates.value == 0
        }.getOrDefault(false)
    }
}
