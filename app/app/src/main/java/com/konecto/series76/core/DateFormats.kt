package com.konecto.series76.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateFormats {
    /** Card date as in the Figma: "08 August 2024 | 03:23 AM". */
    private val card = DateTimeFormatter.ofPattern("dd MMMM yyyy | hh:mm a", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)

    fun card(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        card.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Backend dates are ISO-8601 in UTC ("…Z" or "+00:00"); naive values are taken as UTC. */
    fun parseIso(value: String): Long = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrElse { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
}
