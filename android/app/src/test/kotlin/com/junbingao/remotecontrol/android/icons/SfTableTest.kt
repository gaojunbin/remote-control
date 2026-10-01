package com.junbingao.remotecontrol.android.icons

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The SF Symbol table against the iPhone's own source: every symbol RCUI names is drawn here
 * under the iPhone's spelling, and every glyph's path data is drawable.
 */
class SfTableTest {
    @Test
    fun everySymbolTheIPhoneNamesIsInTheTable() {
        val missing = iPhoneSymbols().filter { Sf.named(it) == null }
        assertTrue("SF Symbols RCUI draws that the table lacks: $missing", missing.isEmpty())
    }

    @Test
    fun everyNameIsTheIPhonesSpellingOnce() {
        val names = Sf.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (symbol in Sf.all) assertSame(symbol, Sf.named(symbol.name))
        assertEquals(null, Sf.named("gearshape.2"))
    }

    @Test
    fun everyGlyphsPathDataParses() {
        for (symbol in Sf.all) {
            for (layer in symbol.layers) {
                assertTrue("${symbol.name} has paths", layer.paths.isNotEmpty())
                for (data in layer.paths) {
                    assertTrue("${symbol.name}: $data", PathParser().parsePathString(data).toNodes().isNotEmpty())
                }
            }
        }
    }

    /**
     * The names after `systemName:` and `systemImage:` in RCUI, both arms of a choice included;
     * a name handed in through a variable is found by its own screen's port.
     */
    private fun iPhoneSymbols(): Set<String> {
        val argument = Regex("""(?:systemName|systemImage)\s*:(.*)""")
        val literal = Regex(""""([^"\\]*)"""")
        val symbolName = Regex("""^[a-z0-9]+(\.[a-z0-9]+)*$""")
        return File("../../ios/Sources/RCUI").walkTopDown().filter { it.extension == "swift" }.flatMap { file ->
            file.readLines().asSequence().mapNotNull { argument.find(it)?.groupValues?.get(1) }.flatMap { rest ->
                literal.findAll(rest).map { it.groupValues[1] }.filter { symbolName.matches(it) }
            }
        }.toSet().also { assertTrue("the scan reads RCUI", it.size > 40) }
    }
}
