package com.junbingao.remotecontrol.android.system

import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.icons.Sf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a menu's words start, decided for the whole menu by what its rows carry (iOS 26). */
class MenuColumnsTest {
    @Test
    fun aMenuOfImagesPutsItsWordsAfterTheImages() {
        val attach = listOf(MenuItem.Action("Photos", Sf.photo), MenuItem.Action("Files", Sf.folder))
        assertEquals(MenuColumns.images, MenuColumns.of(attach))
        assertEquals(64.dp, MenuColumns.images.textInset)
    }

    @Test
    fun aPickerKeepsItsCheckmarkColumn() {
        val picker = listOf(MenuItem.Action("Chinese", checked = true), MenuItem.Action("English"))
        assertEquals(MenuColumns.checks, MenuColumns.of(picker))
        assertEquals(44.dp, MenuColumns.checks.textInset)
    }

    @Test
    fun oneRowWithAnImageDecidesForEveryRow() {
        val filter = listOf(
            MenuItem.Action("All agents", Sf.checkmark, checked = true),
            MenuItem.Section(items = listOf(MenuItem.Action("Codex"), MenuItem.Divider)),
        )
        assertEquals(MenuColumns.images, MenuColumns.of(filter))
    }

    @Test
    fun wordsAloneStartNearTheEdge() {
        assertEquals(MenuColumns.plain, MenuColumns.of(listOf(MenuItem.Action("Rename"), MenuItem.Action("Revoke", role = ActionRole.destructive))))
    }
}
