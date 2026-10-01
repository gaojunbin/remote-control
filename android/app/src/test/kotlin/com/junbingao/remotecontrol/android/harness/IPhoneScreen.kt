package com.junbingao.remotecontrol.android.harness

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import com.junbingao.remotecontrol.android.design.Appearance
import com.junbingao.remotecontrol.android.shell.AppRoot
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.LocalSafeArea
import com.junbingao.remotecontrol.android.system.Presenter
import com.junbingao.remotecontrol.android.system.SafeArea

/**
 * The one way to picture a screen as the iPhone 17 shows it: 402 by 874 points at three pixels a
 * point (1206 by 2622, the size of every picture in the iPhone's reference set), the iPhone's safe
 * area, the app's root around it, in a language and an appearance.
 *
 * A test class carries `@Config(qualifiers = IPhone.QUALIFIERS)` and `@GraphicsMode(NATIVE)`
 * (see [IPhoneScreenshotTest]) and calls [picture] with a name. `./gradlew :app:recordRoborazziDebug`
 * draws the picture into `build/outputs/roborazzi/<group>/<name>-<language>-<appearance>.png`, the
 * output directory the build configures: it is evidence to lay beside the iPhone's picture of the
 * same screen, never a baseline kept in the repository. A plain test run draws nothing.
 */
object IPhone {
    /** Robolectric's screen for the iPhone 17: 402 by 874 dp at xxhdpi, three pixels a dp. */
    const val QUALIFIERS = "w402dp-h874dp-port-xxhdpi"

    /** Every combination a picture is taken in: both languages, both appearances. */
    val variants: List<Variant> = listOf(
        Variant(L10n.english, dark = false),
        Variant(L10n.chinese, dark = false),
        Variant(L10n.english, dark = true),
        Variant(L10n.chinese, dark = true),
    )
}

/** A language and an appearance to picture a screen in. */
data class Variant(val language: String, val dark: Boolean) {
    val suffix: String get() = "${if (language == L10n.chinese) "zh" else "en"}-${if (dark) "dark" else "light"}"
}

/**
 * Draws [content] inside the app's root at the iPhone's size in [variant], waits for it to
 * settle, and records it under [group]/[name].
 */
fun ComposeContentTestRule.picture(
    group: String,
    name: String,
    variant: Variant = Variant(L10n.english, dark = false),
    content: @Composable () -> Unit,
) {
    L10n.use(variant.language)
    setContent { IPhoneFrame(variant) { content() } }
    waitForIdle()
    capture(group, name, variant)
}

/**
 * Records what is on screen now under [group]/[name], for a test that drives the screen first —
 * a tap that opens a menu, a swipe — before the picture is taken.
 */
fun ComposeTestRule.capture(group: String, name: String, variant: Variant) {
    onRoot().captureRoboImage(picturePath(group, name, variant))
}

/** Where the picture of [name] in [variant] is drawn: under the output directory the build configures. */
@OptIn(ExperimentalRoborazziApi::class)
fun picturePath(group: String, name: String, variant: Variant): String =
    "${roborazziSystemPropertyOutputDirectory()}/$group/$name-${variant.suffix}.png"

/**
 * The app's root as a picture needs it: the iPhone's safe area, a fixed appearance, no privacy
 * cover, and [over] above the presentations where the activity draws the lock.
 */
@Composable
fun IPhoneFrame(variant: Variant, over: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val presenter = remember { Presenter() }
    CompositionLocalProvider(LocalSafeArea provides SafeArea.iPhone17) {
        AppRoot(presenter, appearance = Appearance(isDark = variant.dark), shielded = false, over = over) { content() }
    }
}
