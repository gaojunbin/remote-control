package com.junbingao.remotecontrol.android.screens.chat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.strings.L10n
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * `DatePicker(…, displayedComponents: [.date, .hourAndMinute]).datePickerStyle(.compact)`: the
 * label, then the date and the time in two grey pills. A tap on either opens the phone's own
 * picker for it — Android's date and time pickers are the smallest the platform has — bounded to
 * [range], and whatever comes back is held inside it.
 */
@Composable
internal fun CompactDatePicker(
    title: String,
    date: Instant,
    range: ClosedRange<Instant>,
    onDate: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val locale = Locale.forLanguageTag(L10n.language)
    val local = date.atZone(zone)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.weight(1f), style = Theme.Text.label, color = Theme.ink)
        Pill(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(local)) {
            CompactDatePickers.pickDate(context, date, range, zone, onDate)
        }
        Pill(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(local)) {
            CompactDatePickers.pickTime(context, date, range, zone, onDate)
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Button(onClick) {
        Text(
            text,
            Modifier
                .background(SystemColor.tertiarySystemFill, ContinuousShape(8.dp))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            style = SystemFont.body,
            color = Theme.ink,
            lineLimit = 1,
        )
    }
}

internal object CompactDatePickers {
    fun pickDate(context: Context, date: Instant, range: ClosedRange<Instant>, zone: ZoneId, onDate: (Instant) -> Unit) {
        val current = date.atZone(zone)
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            val picked = LocalDate.of(year, month + 1, day).atTime(current.toLocalTime()).atZone(zone).toInstant()
            onDate(clamp(picked, range))
        }, current.year, current.monthValue - 1, current.dayOfMonth)
        dialog.datePicker.minDate = range.start.toEpochMilli()
        dialog.datePicker.maxDate = range.endInclusive.toEpochMilli()
        dialog.show()
    }

    fun pickTime(context: Context, date: Instant, range: ClosedRange<Instant>, zone: ZoneId, onDate: (Instant) -> Unit) {
        val current = date.atZone(zone)
        TimePickerDialog(context, { _, hour, minute ->
            val picked = current.toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
            onDate(clamp(picked, range))
        }, current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
    }

    /** What a picker hands back, held inside the bounds it was opened with. */
    fun clamp(date: Instant, range: ClosedRange<Instant>): Instant = when {
        date < range.start -> range.start
        date > range.endInclusive -> range.endInclusive
        else -> date
    }
}
