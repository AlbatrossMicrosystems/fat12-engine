package com.ams.fat12ex.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [decodeFatDate] / [decodeFatTime] and the [Fat12Entry] decode accessors.
 *
 * Fixtures are hand-computed from the FAT bit packing:
 *   date = ((year-1980) shl 9) or (month shl 5) or day
 *   time = (hours shl 11) or (minutes shl 5) or (seconds / 2)
 */
class FatDateTimeTest {

    // ----- decodeFatDate ------------------------------------------------------

    @Test
    fun decodeFatDate_knownValue() {
        // 2026-06-18 = (46 shl 9) or (6 shl 5) or 18 = 23762 (0x5CD2)
        assertEquals(LocalDate.of(2026, 6, 18), decodeFatDate(0x5CD2))
    }

    @Test
    fun decodeFatDate_engineDefault() {
        // DirRegion.DEFAULT_DOS_DATE (0x58C1) = 2024-06-01
        assertEquals(LocalDate.of(2024, 6, 1), decodeFatDate(0x58C1))
    }

    @Test
    fun decodeFatDate_epochYear1980_offsetZero() {
        // year offset 0, month 1, day 1 = (0 shl 9) or (1 shl 5) or 1 = 0x0021
        assertEquals(LocalDate.of(1980, 1, 1), decodeFatDate(0x0021))
    }

    @Test
    fun decodeFatDate_zeroIsNull() {
        assertNull(decodeFatDate(0x0000))
    }

    @Test
    fun decodeFatDate_invalidComponentsIsNull() {
        // month = 0 (day 1) — not a valid calendar date.
        assertNull(decodeFatDate(0x0001))
        // month = 13, day = 1, year offset 0 = (13 shl 5) or 1 = 0x01A1 — invalid month.
        assertNull(decodeFatDate(0x01A1))
    }

    @Test
    fun decodeFatDate_ignoresHighBits() {
        // High bits above the 16-bit field must not affect the result.
        assertEquals(decodeFatDate(0x5CD2), decodeFatDate(0x5CD2 or 0x7FFF_0000))
    }

    // ----- decodeFatTime ------------------------------------------------------

    @Test
    fun decodeFatTime_knownValue() {
        // 13:45:30 = (13 shl 11) or (45 shl 5) or (30 / 2) = 28079 (0x6DAF)
        assertEquals(LocalTime.of(13, 45, 30), decodeFatTime(0x6DAF))
    }

    @Test
    fun decodeFatTime_engineDefault() {
        // DirRegion.DEFAULT_DOS_TIME (0x6000) = 12:00:00
        assertEquals(LocalTime.of(12, 0, 0), decodeFatTime(0x6000))
    }

    @Test
    fun decodeFatTime_zeroIsMidnight() {
        assertEquals(LocalTime.of(0, 0, 0), decodeFatTime(0x0000))
    }

    @Test
    fun decodeFatTime_roundTripsValidFields_secondsAlwaysEven() {
        // Over the representable space (2-second resolution) the decode round-trips
        // the packed components, and seconds are always even.
        for (hours in 0..23) {
            for (minutes in intArrayOf(0, 1, 30, 59)) {
                for (secHalf in 0..29) {
                    val packed = (hours shl 11) or (minutes shl 5) or secHalf
                    val decoded = decodeFatTime(packed)
                    assertEquals(LocalTime.of(hours, minutes, secHalf * 2), decoded)
                    assertTrue(decoded.second % 2 == 0)
                }
            }
        }
    }

    @Test
    fun decodeFatTime_maxSecondsField() {
        // seconds field = 29 -> 58s; minutes 0, hours 0 -> 0x001D.
        assertEquals(LocalTime.of(0, 0, 58), decodeFatTime(0x001D))
    }

    // ----- Fat12Entry accessors ----------------------------------------------

    @Test
    fun entryAccessors_decodeRawFields() {
        val entry = Fat12Entry(
            name = "REPORT.TXT",
            shortName = "REPORT  TXT",
            isDirectory = false,
            size = 42,
            firstCluster = 2,
            attributes = 0x20,      // ARCHIVE
            createdDate = 0x5CD2,   // 2026-06-18
            modifiedDate = 0x58C1,  // 2024-06-01
            modifiedTime = 0x6DAF,  // 13:45:30
        )
        assertEquals(LocalDate.of(2026, 6, 18), entry.createdDateOrNull())
        assertEquals(LocalDate.of(2024, 6, 1), entry.modifiedDateOrNull())
        assertEquals(
            LocalDateTime.of(2024, 6, 1, 13, 45, 30),
            entry.modifiedDateTimeOrNull(),
        )
    }

    @Test
    fun entryAccessors_zeroDateFieldsAreNull() {
        val entry = Fat12Entry(
            name = "EMPTY.TXT",
            shortName = "EMPTY   TXT",
            isDirectory = false,
            size = 0,
            firstCluster = 0,
            attributes = 0,
            createdDate = 0,
            modifiedDate = 0,
            modifiedTime = 0,
        )
        assertNull(entry.createdDateOrNull())
        assertNull(entry.modifiedDateOrNull())
        assertNull(entry.modifiedDateTimeOrNull())
    }

    @Test
    fun entryAccessors_corruptModifiedTime_decoderThrowsButAccessorIsNull() {
        // modifiedTime = 0x001E has a seconds-field of 30 -> 60s, which is not a
        // valid LocalTime. The low-level decoder rejects it; the entry accessor
        // swallows that to null rather than propagating the exception.
        assertThrows<java.time.DateTimeException> { decodeFatTime(0x001E) }

        val entry = Fat12Entry(
            name = "BROKEN.TXT",
            shortName = "BROKEN  TXT",
            isDirectory = false,
            size = 1,
            firstCluster = 2,
            attributes = 0x20,
            createdDate = 0x5CD2,   // valid date
            modifiedDate = 0x58C1,  // valid date
            modifiedTime = 0x001E,  // corrupt time
        )
        assertNull(entry.modifiedDateTimeOrNull())
    }
}
