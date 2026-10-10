package com.streamvault.feature.live.presentation.epg

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun startOfGuideDay(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
    val localDate = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
    return localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
}

fun shiftGuideDayStart(
    dayStartMillis: Long,
    days: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    val localDate = Instant.ofEpochMilli(dayStartMillis).atZone(zoneId).toLocalDate().plusDays(days)
    return localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
}

fun shiftGuideAnchorByDays(
    anchorTimeMillis: Long,
    days: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long = Instant.ofEpochMilli(anchorTimeMillis)
    .atZone(zoneId)
    .plusDays(days)
    .toInstant()
    .toEpochMilli()

fun guidePrimeTimeAnchor(
    anchorTimeMillis: Long,
    primeTimeHour: Int,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    val localDate = Instant.ofEpochMilli(anchorTimeMillis).atZone(zoneId).toLocalDate()
    return localDate.atTime(primeTimeHour, 0).atZone(zoneId).toInstant().toEpochMilli()
}

fun jumpGuideAnchorToDay(
    anchorTimeMillis: Long,
    dayStartMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    val anchorDateTime = Instant.ofEpochMilli(anchorTimeMillis).atZone(zoneId)
    val targetDate = Instant.ofEpochMilli(dayStartMillis).atZone(zoneId).toLocalDate()
    return targetDate.atTime(anchorDateTime.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
}

fun dayRelativeOffset(
    dayStartMillis: Long,
    today: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    val day = Instant.ofEpochMilli(dayStartMillis).atZone(zoneId).toLocalDate()
    return day.toEpochDay() - today.toEpochDay()
}

/**
 * The anchor the guide should show when it becomes visible again. A future anchor was chosen
 * on purpose (tonight, tomorrow) and is kept; one that has already slipped into the past is
 * just the moment the guide was last left, possibly hours ago, so it follows the clock.
 */
fun guideAnchorOnReturn(anchorTimeMillis: Long, nowMillis: Long, staleAfterMillis: Long): Long =
    if (anchorTimeMillis < nowMillis - staleAfterMillis) nowMillis else anchorTimeMillis
