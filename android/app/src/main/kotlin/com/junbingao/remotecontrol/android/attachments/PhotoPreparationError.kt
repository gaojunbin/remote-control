package com.junbingao.remotecontrol.android.attachments

import com.junbingao.remotecontrol.android.strings.L10n

/**
 * Why a photo could not be attached. The words are built when they are read, through `L10n`,
 * because the composer shows them as they are and a stored sentence would stay in the language it
 * was made in.
 */
sealed class PhotoPreparationError : Exception() {
    data object InvalidImage : PhotoPreparationError() {
        override val message: String get() = L10n.string("That image could not be read.")
    }

    data object ImageTooLarge : PhotoPreparationError() {
        override val message: String get() = L10n.string("That image is too large. Pick a smaller one.")
    }
}
