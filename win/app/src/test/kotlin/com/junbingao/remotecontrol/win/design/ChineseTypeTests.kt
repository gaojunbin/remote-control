package com.junbingao.remotecontrol.win.design

import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Chinese as Chrome sets it: `lang` the interface language, the web's font stack, 13 px / 1.45
 * (the Settings row sentence). The widths are Chrome's on macOS 27, where the Mac app's own checks
 * were measured; on a Mac this app sets the same faces.
 */
@EnabledOnOs(OS.MAC)
class ChineseTypeTests {
    private val polish = "开启后，会把你的听写内容和最近几条消息发给此网关配置的模型；关闭时不发送任何内容。"
    private val style = TextStyle(size = FontSize.fs13, lineHeight = 1.45f)

    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun aLineMeasuresWhatChromesDoesUnderZhHans() {
        val measured = listOf(
            polish to 533.00f,
            "“适度”只做清理；“加强”还会重组语句并明确指代。" to 296.56f,
            "简约只显示写给你的内容。详细会加上思考、工具调用和任务清单。" to 390.00f,
            "缓存的会话和草稿将从此设备移除，你的机器不受影响。" to 325.00f,
        )
        for ((text, chrome) in measured) {
            val width = Measure.width(text, style, InterfaceLanguage.zhHans)
            assertTrue(abs(width - chrome) < 1f, "${text.take(6)}: $width against $chrome")
        }
    }

    /**
     * Chrome breaks after 30 characters at 400 px, and puts the last two characters of the note on
     * a line of their own at 520 and 530 px, where the full stop may not start a line.
     */
    @Test
    fun wrappedLinesAreChromesUnderZhHans() {
        val three = Measure.layout(polish + polish + polish, style, InterfaceLanguage.zhHans, width = 400)
        assertEquals(5, three.lineCount)
        assertEquals(30, three.getLineEnd(0))
        for (width in listOf(520, 530)) {
            val one = Measure.layout(polish, style, InterfaceLanguage.zhHans, width = width)
            assertEquals(2, one.lineCount, "$width px")
            assertEquals(polish.length - 2, one.getLineStart(1), "$width px")
        }
    }

    /** Under `en` Chrome draws Han in the system face's own cascade, as CoreText does. */
    @Test
    fun chineseInAnEnglishInterfaceIsTheSystemFacesCascade() {
        val width = Measure.width("中文", TextStyle(size = FontSize.fs14))
        assertTrue(width >= 27.78f && width < 28.8f, "$width")
    }

    /**
     * Settings' Segmented controls, sized by their labels: Chrome's widths of the whole control,
     * 3 px inset and between, each segment its label and the button's 6 px on either side.
     */
    @Test
    fun aSegmentedSizedByItsLabelsIsChromes() {
        val measured = listOf(
            Triple(InterfaceLanguage.en, "Moderate" to "Strong", 138.16f),
            Triple(InterfaceLanguage.en, "English" to "中文", 107.36f),
            Triple(InterfaceLanguage.en, "Simple" to "Detailed", 130.13f),
            Triple(InterfaceLanguage.zhHans, "适度" to "加强", 89.00f),
            Triple(InterfaceLanguage.zhHans, "English" to "中文", 107.56f),
            Triple(InterfaceLanguage.zhHans, "简约" to "详细", 89.00f),
        )
        for ((language, labels, chrome) in measured) {
            InterfaceLanguageSource.current = language
            val (first, second) = labels
            val width = Measure.size {
                Segmented(first, listOf(SegmentOption(first, first), SegmentOption(second, second)), "") {}
            }.width
            assertTrue(abs(width - chrome) < 1.5f, "$first | $second: $width against $chrome")
        }
    }
}
