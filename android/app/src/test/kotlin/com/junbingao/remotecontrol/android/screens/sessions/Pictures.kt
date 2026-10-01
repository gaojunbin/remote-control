package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.test.junit4.ComposeTestRule
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The lists' pictures in the variants their ported tests do not take — Chinese, and both languages
 * dark — for laying beside the English, light ones and the iPhone's. A picture keeps its iPhone
 * test's folder and its step's name, so `<test>/<shot>-zh-dark.png` sits beside `<shot>-en-light.png`.
 * The steps are the ported tests'; their assertions, which read English, stay there.
 */
internal object Pictures {
    val variants: List<Variant> = IPhone.variants.filterNot { it == Variant(L10n.english, dark = false) }

    /** Launch the demo once per variant, as the iPhone test [test] launches it, and run [steps]. */
    fun each(compose: ComposeTestRule, test: String, steps: (DemoApp, ListsDriver) -> Unit) {
        for (variant in variants) DemoApp(compose, test, variant = variant).use { app -> steps(app, ListsDriver(compose, app)) }
    }
}
