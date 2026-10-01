package com.junbingao.remotecontrol.win.chat.resume

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.ChatStretchedPrimaryStyle
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.LocalShowsCaret
import com.junbingao.remotecontrol.win.design.NaturalLine
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.SystemFace
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.TextRendering
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * `ChangeForm` in `ResumeNotice.tsx`: the smallest time picker the platform has — the web's
 * `datetime-local` field — prefilled with the time the device holds, and checked on Set against a
 * minute and eight days away, because a typed value reaches neither bound. The refusal is this
 * app's own sentence.
 */
@Composable
fun ResumeChangeForm(resume: SessionResume, onSet: suspend (Long) -> Boolean, onDone: () -> Unit) {
    val model = LocalAppModel.current
    var text by remember { mutableStateOf(ResumeFieldFormat.text(resume.date)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        val date = ResumeFieldFormat.date(text)
        if (date == null) {
            error = S.chat.resumeTooSoon
            return
        }
        val at = date.toEpochMilli().toDouble().roundToLong()
        ResumeWords.boundError(at)?.let {
            error = it
            return
        }
        error = null
        busy = true
        model.tasks.launch {
            val taken = onSet(at)
            busy = false
            if (taken) onDone() else error = S.errors.resumeSetFailed
        }
    }
    VStack(Modifier.widthIn(min = 232.dp).padding(Space.sp3), spacing = Space.sp2, alignment = Alignment.Start) {
        Text(S.chat.resumeAt, css(FontSize.fs12), color = Palette.inkSecondary)
        ResumeDateField(text) {
            text = it
            error = null
        }
        error?.let { Text(it, css(FontSize.fs12), color = Palette.danger) }
        Disabled(busy) {
            Button(::submit, Modifier.fillMaxWidth(), style = ChatStretchedPrimaryStyle) {
                Text(S.chat.resumeSet, css(FontSize.fs13, weight = FontWeight.Medium), softWrap = false)
            }
        }
    }
}

/**
 * What the field shows and reads: a time to the minute, as the browser's `datetime-local` field
 * draws it — the short date and time of the person's language, every number at the field's width:
 * "2026/09/28 23:50", "09/28/2026, 11:50 PM". The browser takes that from the system, not from
 * the page, so the interface language does not change it.
 */
object ResumeFieldFormat {
    fun text(date: Instant): String = formatter.format(date.atZone(ZoneId.systemDefault()))

    /** The time the field names, or null when it names none. */
    fun date(text: String): Instant? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { LocalDateTime.parse(trimmed, formatter).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    private val formatter: DateTimeFormatter by lazy {
        val locale = browserLocale
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        DateTimeFormatter.ofPattern(fieldPattern(pattern), locale)
    }

    /**
     * The person's first language without its region, which is how the browser picks the
     * language it draws its own controls in: "zh-Hans-SG" draws as Simplified Chinese does, not as
     * Singapore's dates are written.
     */
    private val browserLocale: Locale
        get() {
            val preferred = Locale.getDefault(Locale.Category.DISPLAY)
            if (preferred.language.isEmpty()) return Locale.ENGLISH
            return Locale.Builder().setLanguage(preferred.language).setScript(preferred.script).build()
        }

    /** A pattern with every number at the width the browser's field draws it: four digits of year, two of everything else. Quoted text is kept. */
    fun fieldPattern(pattern: String): String {
        val out = StringBuilder()
        var quoted = false
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            var end = index + 1
            if (character == '\'') {
                quoted = !quoted
                out.append(character)
                index = end
                continue
            }
            while (!quoted && end < pattern.length && pattern[end] == character) end++
            when {
                !quoted && character == 'y' -> out.append("yyyy")
                !quoted && character in "MdhHm" -> out.append(character).append(character)
                else -> out.append(pattern, index, end)
            }
            index = end
        }
        return out.toString()
    }
}

/**
 * `.resume-form-field`: 32 points tall with its edge, a strong edge on an 8-point radius, 13-point
 * text 8 points in, the edge turning ink while the field has the keyboard.
 */
@Composable
private fun ResumeDateField(text: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val face = SystemFace.face(FontSize.fs13, FontWeight.Normal, mono = false)
    val line = NaturalLine.of(FontSize.fs13, FontWeight.Normal, mono = false)
    val style = ComposeTextStyle(
        color = Palette.ink,
        fontSize = FontSize.fs13.sp,
        fontFamily = face.family,
        letterSpacing = face.tracking.sp,
        lineHeight = line.height.sp,
        platformStyle = TextRendering.platformStyle,
        localeList = LocaleList(InterfaceLanguageSource.current.rawValue),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .chatBorder(ChatBorder(width = 1.dp, radius = Radius.sm), if (focused) Palette.ink else Palette.lineStrong)
            .background(Palette.surface, RoundedCornerShape(Radius.sm))
            // Inside the edge, the padding and the point the browser keeps on either side of each
            // of the field's numbers; its text sits a point higher than the field centres it.
            .padding(start = Space.sp2 + 2.dp, end = Space.sp2 + 2.dp, bottom = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = S.chat.resumeAt },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(if (LocalShowsCaret.current) Palette.ink else Color.Transparent),
        )
    }
}
