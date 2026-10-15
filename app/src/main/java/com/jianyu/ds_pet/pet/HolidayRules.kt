package com.jianyu.ds_pet.pet

import java.time.LocalDate

internal data class HolidaySpan(val title: String, val start: LocalDate, val endExclusive: LocalDate)

/** 日历里标成补班、上班的那几天是工作日，不当成假日。 */
internal fun isMakeupWorkTitle(title: String): Boolean =
    title.contains("补班") || title.contains("補班") || title.contains("上班") ||
        title.contains("调班") || title.contains("調班")

internal fun datesCoveredBy(events: List<HolidaySpan>): Set<LocalDate> {
    val out = linkedSetOf<LocalDate>()
    for (event in events) {
        if (isMakeupWorkTitle(event.title)) continue
        var day = event.start
        val end = if (event.endExclusive > event.start) event.endExclusive else event.start.plusDays(1)
        var guard = 0
        while (day < end && guard < 40) {
            out += day
            day = day.plusDays(1)
            guard++
        }
    }
    return out
}

internal fun isChineseHolidayCalendar(displayName: String, name: String, accountName: String): Boolean {
    val blob = "$displayName\n$name\n$accountName"
    val lower = blob.lowercase()
    val china = lower.contains("china") || lower.contains("chinese") ||
        blob.contains("中国") || blob.contains("大陸") || blob.contains("大陆")
    val otherCountry = listOf(
        "united states", "japan", "japanese", "united kingdom", "korea",
        "台湾", "台灣", "香港", "hong kong", "日本", "美国", "美國", "韩国", "韓國",
    ).any { lower.contains(it) || blob.contains(it) }
    if (otherCountry && !china) return false
    if (blob.contains("节假日") || blob.contains("節假日")) return true
    if (lower.contains("#holiday")) return china || lower.contains("zh")
    if (lower.contains("holiday")) return china
    return false
}

/**
 * 国务院办公厅已经公布的放假调休日期（连休的每一天，不只放假办法里的 13 天）。
 * 手机日历读不到时用这份；读到了会和日历并在一起。
 */
internal fun officialChineseVacation(date: LocalDate): Boolean {
    val ranges = VACATION[date.year] ?: return false
    return ranges.any { date >= it.first && date <= it.second }
}

private val VACATION: Map<Int, List<Pair<LocalDate, LocalDate>>> = mapOf(
    2025 to listOf(
        span(2025, 1, 1, 2025, 1, 1),
        span(2025, 1, 28, 2025, 2, 4),
        span(2025, 4, 4, 2025, 4, 6),
        span(2025, 5, 1, 2025, 5, 5),
        span(2025, 5, 31, 2025, 6, 2),
        span(2025, 10, 1, 2025, 10, 8),
    ),
    2026 to listOf(
        span(2026, 1, 1, 2026, 1, 3),
        span(2026, 2, 15, 2026, 2, 23),
        span(2026, 4, 4, 2026, 4, 6),
        span(2026, 5, 1, 2026, 5, 5),
        span(2026, 6, 19, 2026, 6, 21),
        span(2026, 9, 25, 2026, 9, 27),
        span(2026, 10, 1, 2026, 10, 7),
    ),
)

private fun span(y1: Int, m1: Int, d1: Int, y2: Int, m2: Int, d2: Int): Pair<LocalDate, LocalDate> =
    LocalDate.of(y1, m1, d1) to LocalDate.of(y2, m2, d2)
