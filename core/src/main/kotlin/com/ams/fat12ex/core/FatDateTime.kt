package com.ams.fat12ex.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pure decoders for the DOS-packed 16-bit date/time fields carried raw on
 * [Fat12Entry] ([Fat12Entry.createdDate], [Fat12Entry.modifiedDate],
 * [Fat12Entry.modifiedTime]).
 *
 * **Date** (16-bit — [Fat12Entry.createdDate] / [Fat12Entry.modifiedDate]):
 * - bits 0–4:  day of month (1–31)
 * - bits 5–8:  month (1–12)
 * - bits 9–15: year offset from 1980
 *
 * **Time** (16-bit — [Fat12Entry.modifiedTime]):
 * - bits 0–4:  seconds / 2 (0–29 → 0–58s, 2-second resolution)
 * - bits 5–10: minutes (0–59)
 * - bits 11–15: hours (0–23)
 *
 * These are additive helpers; the raw `Int` fields on [Fat12Entry] are unchanged.
 */

/**
 * Decode a FAT-packed 16-bit date field into a [LocalDate].
 *
 * Returns `null` for the zero sentinel (`0x0000` = the invalid DOS date
 * 1980-00-00 strict readers reject) and for any bit pattern that does not form a
 * valid calendar date (e.g. month 0 or day 30 of February in a corrupt entry).
 * Only the low 16 bits of [packed] are considered.
 */
fun decodeFatDate(packed: Int): LocalDate? {
    val bits = packed and 0xFFFF
    if (bits == 0) return null
    val day = bits and 0x1F
    val month = (bits ushr 5) and 0x0F
    val year = 1980 + ((bits ushr 9) and 0x7F)
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/**
 * Decode a FAT-packed 16-bit time field into a [LocalTime] (2-second resolution;
 * odd seconds are not representable and are truncated to the lower even value by
 * the encoding).
 *
 * The three components are masked to their bit-widths; the hours (0–23), minutes
 * (0–59) and seconds-field (0–29) of a well-formed entry are always in range. A
 * corrupt field whose decoded components fall outside those ranges (e.g. a
 * seconds-field of 30/31 decoding to 60/62s) throws [java.time.DateTimeException].
 * Only the low 16 bits of [packed] are considered.
 */
fun decodeFatTime(packed: Int): LocalTime {
    val bits = packed and 0xFFFF
    val seconds = (bits and 0x1F) * 2
    val minutes = (bits ushr 5) and 0x3F
    val hours = (bits ushr 11) and 0x1F
    return LocalTime.of(hours, minutes, seconds)
}

/** The decoded creation date, or `null` when the raw [Fat12Entry.createdDate] field is absent/invalid. */
fun Fat12Entry.createdDateOrNull(): LocalDate? = decodeFatDate(createdDate)

/** The decoded last-modified date, or `null` when the raw [Fat12Entry.modifiedDate] field is absent/invalid. */
fun Fat12Entry.modifiedDateOrNull(): LocalDate? = decodeFatDate(modifiedDate)

/**
 * The decoded last-modified date+time, or `null` when the raw modified date is
 * absent/invalid. The modified time is combined with the modified date; a corrupt
 * time field yields `null` rather than throwing.
 */
fun Fat12Entry.modifiedDateTimeOrNull(): LocalDateTime? {
    val date = modifiedDateOrNull() ?: return null
    return runCatching { LocalDateTime.of(date, decodeFatTime(modifiedTime)) }.getOrNull()
}
