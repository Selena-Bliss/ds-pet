package com.jianyu.ds_pet.pet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal class PricingHolidays(private val context: Context) {
    private var stamp = ""
    private var deviceDates = emptySet<LocalDate>()

    fun isHoliday(date: LocalDate): Boolean {
        val today = LocalDate.now(SHANGHAI)
        val granted = calendarGranted(context)
        val key = "$today:$granted"
        if (key != stamp) {
            stamp = key
            deviceDates = if (granted) {
                runCatching { readChineseHolidayDates(context, today.year) }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
        }
        return date in deviceDates || officialChineseVacation(date)
    }

    private companion object {
        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

internal fun calendarGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

internal fun chineseHolidayCalendarCount(context: Context): Int =
    if (calendarGranted(context)) chineseHolidayCalendarIds(context).size else 0

internal fun readChineseHolidayDates(context: Context, year: Int): Set<LocalDate> {
    val ids = chineseHolidayCalendarIds(context)
    if (ids.isEmpty()) return emptySet()
    val start = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
        android.content.ContentUris.appendId(this, start)
        android.content.ContentUris.appendId(this, end)
    }.build()
    val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")}) AND ${CalendarContract.Instances.ALL_DAY}=1"
    val events = mutableListOf<HolidaySpan>()
    context.contentResolver.query(
        uri,
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
        selection,
        null,
        null,
    )?.use { cursor ->
        val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
        val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
        val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
        while (cursor.moveToNext()) {
            if (beginIdx < 0 || endIdx < 0) continue
            val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else ""
            val startDate = Instant.ofEpochMilli(cursor.getLong(beginIdx)).atZone(ZoneOffset.UTC).toLocalDate()
            val endDate = Instant.ofEpochMilli(cursor.getLong(endIdx)).atZone(ZoneOffset.UTC).toLocalDate()
            events += HolidaySpan(title, startDate, endDate)
        }
    }
    return datesCoveredBy(events)
}

private fun chineseHolidayCalendarIds(context: Context): List<Long> {
    val ids = mutableListOf<Long>()
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
        val displayIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val nameIdx = cursor.getColumnIndex(CalendarContract.Calendars.NAME)
        val accountIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
        while (cursor.moveToNext()) {
            if (idIdx < 0) continue
            val display = if (displayIdx >= 0) cursor.getString(displayIdx).orEmpty() else ""
            val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
            val account = if (accountIdx >= 0) cursor.getString(accountIdx).orEmpty() else ""
            if (isChineseHolidayCalendar(display, name, account)) ids += cursor.getLong(idIdx)
        }
    }
    return ids
}
