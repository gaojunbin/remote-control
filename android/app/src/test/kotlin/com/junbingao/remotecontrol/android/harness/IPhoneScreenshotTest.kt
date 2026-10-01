package com.junbingao.remotecontrol.android.harness

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.L10n as CoreL10n
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The base of every picture test: the iPhone 17's screen, the real graphics stack, a compose rule,
 * and English put back afterwards — in the app's words and the core's — so no test inherits
 * another's language.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
abstract class IPhoneScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun englishAgain() {
        L10n.use(L10n.english)
        CoreL10n.use(InterfaceLanguage.en)
    }
}
