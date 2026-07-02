package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FatAttributes] tests — the public, named FAT directory-entry attribute bits.
 *
 * Proves the bit values match the FAT spec (the attribute byte at entry offset
 * 0x0B), that they combine with `or` as documented, and that they round-trip
 * through [Fat12Volume.setAttributes] -> [Fat12Entry.attributes].
 */
class FatAttributesTest {

    // ----- case 1: the constants equal their FAT-spec bit values -------------

    @Test
    fun bitValues_matchTheFatSpec() {
        assertEquals(0x01, FatAttributes.READ_ONLY)
        assertEquals(0x02, FatAttributes.HIDDEN)
        assertEquals(0x04, FatAttributes.SYSTEM)
        assertEquals(0x08, FatAttributes.VOLUME_ID)
        assertEquals(0x10, FatAttributes.DIRECTORY)
        assertEquals(0x20, FatAttributes.ARCHIVE)
    }

    // ----- case 2: bits combine with `or` -------------------------------------

    @Test
    fun bits_combineWithOr() {
        assertEquals(0x03, FatAttributes.READ_ONLY or FatAttributes.HIDDEN)
        assertEquals(0x07, FatAttributes.READ_ONLY or FatAttributes.HIDDEN or FatAttributes.SYSTEM)
        assertEquals(0x21, FatAttributes.READ_ONLY or FatAttributes.ARCHIVE)
        // The four user bits together are the engine's USER_ATTR_MASK (0x27).
        val userBits = FatAttributes.READ_ONLY or FatAttributes.HIDDEN or
            FatAttributes.SYSTEM or FatAttributes.ARCHIVE
        assertEquals(0x27, userBits)
    }

    // ----- case 3: the constants round-trip through setAttributes -------------

    @Test
    fun constants_roundTripThroughSetAttributes() {
        val device: InMemoryBlockDevice =
            Fat12ImageBuilder()
                .withReservedShortEntry(
                    name83 = "DATA    BIN",
                    attr = FatAttributes.ARCHIVE,
                    clusters = listOf(2),
                    bytes = ByteArray(16) { it.toByte() },
                )
                .build()
        val vol = Fat12Volume(device).apply { open() }

        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/DATA.BIN", FatAttributes.READ_ONLY or FatAttributes.HIDDEN),
        )

        val entry = (vol.list("") as Fat12Result.Ok).value
            .first { it.name.equals("DATA.BIN", ignoreCase = true) }
        assertTrue((entry.attributes and FatAttributes.READ_ONLY) != 0, "R must be set")
        assertTrue((entry.attributes and FatAttributes.HIDDEN) != 0, "H must be set")
        assertEquals(0, entry.attributes and FatAttributes.SYSTEM, "S must be clear")
        assertEquals(0, entry.attributes and FatAttributes.ARCHIVE, "A must have been cleared")
    }
}
