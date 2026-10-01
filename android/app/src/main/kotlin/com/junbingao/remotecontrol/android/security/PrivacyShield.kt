package com.junbingao.remotecontrol.android.security

import android.app.Activity
import android.os.Build

/**
 * The platform half of the privacy shield (`PrivacyShield.swift`: what the app switcher sees
 * instead of a transcript). `docs/DESIGN.md` § "The Android app": the shield keeps the content
 * out of the recents screen.
 *
 * From Android 13 the activity asks the system not to keep a picture of it at all. Below that the
 * picture is taken as the activity leaves the front, so the other half — the cover the shell
 * draws whenever [SceneRule.shields] says so — is what the recents screen shows. Screenshots the
 * person takes are not blocked: the iPhone does not block them either.
 */
object PrivacyShield {
    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
    }
}
