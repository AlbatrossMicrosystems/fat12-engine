package com.ams.fat12ex.core

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.NumericChars
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Property-based LFN encode↔decode round-trip for [DirRegion].
 *
 * The LFN preamble is silent-corruption-prone: the
 * reverse-ordinal physical layout, the `nEntries or 0x40` flag on the first
 * physical slot, the per-13-char UTF-16LE split across the 0x01/0x0E/0x1C field
 * groups, and the identical checksum on every slot must ALL be consistent or the
 * entire long name is silently dropped by a real reader. This property generates
 * arbitrary display names, encodes them with [DirRegion.encodeLfnPreamble], lays
 * the preamble + a trailing short entry into a directory buffer, walks it, and
 * reconstructs the LFN from the preamble bytes — asserting the decoded name equals
 * the original. It thus exercises the production encode AND the on-disk byte layout
 * for names a fixed example set would never enumerate.
 *
 * Uses jqwik 1.10.1 (`@Property` + constrained `@ForAll` String).
 */
class DirRegionPropertyTest {

    private val short83 = "LONGNA~1TXT"

    /**
     * Decode the LFN display name for [entry] by reading its
     * [DirRegion.WalkedEntry.lfnEntryCount] preamble slots back out of [dirBytes].
     * Preamble slots are stored PHYSICALLY in reverse order (highest ordinal first /
     * lowest address), so we read each slot's sequence number (byte 0, masking the
     * 0x40 last-entry flag) and place its 13 UTF-16LE chars at `(seq-1)*13`. This is
     * the exact inverse of [DirRegion.encodeLfnPreamble], so a faithful encode makes
     * the round-trip identity.
     */
    private fun decodeLfn(dirBytes: ByteArray, entry: DirRegion.WalkedEntry): String {
        val preambleStart = entry.shortEntryOffset - entry.lfnEntryCount * DirRegion.ENTRY_SIZE
        val totalChars = entry.lfnEntryCount * 13
        val chars = CharArray(totalChars)
        for (i in 0 until entry.lfnEntryCount) {
            val slot = preambleStart + i * DirRegion.ENTRY_SIZE
            val seq = dirBytes[slot].toInt() and 0x3F   // strip the 0x40 last-entry flag
            val base = (seq - 1) * 13
            for (c in 0 until 13) {
                val dst = when {
                    c < 5 -> 0x01 + c * 2
                    c < 11 -> 0x0E + (c - 5) * 2
                    else -> 0x1C + (c - 11) * 2
                }
                val lo = dirBytes[slot + dst].toInt() and 0xFF
                val hi = dirBytes[slot + dst + 1].toInt() and 0xFF
                chars[base + c] = ((hi shl 8) or lo).toChar()
            }
        }
        // encodeLfnPreamble writes UTF-16 0x0000 at char index == displayName.length
        // and 0xFFFF pad beyond it; stop at the first of either terminator.
        val sb = StringBuilder()
        for (ch in chars) {
            if (ch.code == 0x0000 || ch.code == 0xFFFF) break
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun roundTrip(displayName: String): String {
        // ceil(len/13) preamble entries + 1 short entry; size the buffer generously.
        val nEntries = (displayName.length + 12) / 13
        val buf = ByteArray((nEntries + 2) * DirRegion.ENTRY_SIZE)
        val preamble = DirRegion.encodeLfnPreamble(displayName, short83)
        preamble.forEachIndexed { i, slot -> slot.copyInto(buf, i * DirRegion.ENTRY_SIZE) }
        DirRegion.writeShortEntryGeneric(
            dirBytes = buf,
            offset = nEntries * DirRegion.ENTRY_SIZE,
            name83 = short83,
            attr = DirRegion.ATTR_ARCHIVE,
            caseByte = 0,
            firstCluster = 7,
            fileSize = 42,
            dosDate = DirRegion.DEFAULT_DOS_DATE,
            dosTime = DirRegion.DEFAULT_DOS_TIME,
        )
        val entry = DirRegion.walk(buf).single()
        assertEquals(nEntries, entry.lfnEntryCount, "walk must group all $nEntries LFN preamble slots")
        return decodeLfn(buf, entry)
    }

    /**
     * Round-trip property for arbitrary alphanumeric display names of length 1..64.
     * Constrained to the LFN-safe printable set (letters + digits) so the generated
     * name never collides with the 0x0000 terminator or the field structure; this is
     * the "arbitrary display name" surface the plan mandates (printable, length 1..64).
     */
    @Property(tries = 2_000)
    fun lfn_encodeDecode_roundTrip(
        @ForAll @AlphaChars @NumericChars @StringLength(min = 1, max = 64) name: String,
    ) {
        assertEquals(name, roundTrip(name), "LFN encode→decode must reconstruct the display name")
    }

    /**
     * Pins the LFN boundary cases EXPLICITLY (the off-by-13 split corners + a name
     * with separators/dots like a real filename) so they are covered regardless of
     * how the property engine samples: 1 char (1 slot), exactly 13 (1 full slot),
     * 14 (2 slots, 1 char in the second), 26 (2 full slots), and a realistic 43-char
     * filename.
     */
    @Test
    fun lfn_boundaryLengths_roundTrip() {
        val names = listOf(
            "A",
            "1234567890123",                                   // 13 — exactly one full slot
            "12345678901234",                                  // 14 — spills into a 2nd slot
            "12345678901234567890123456",                      // 26 — two full slots
            "sample_long_lfn_filename_for_testing_v2.bin",      // 43 — realistic, 4 slots
        )
        for (n in names) {
            assertEquals(n, roundTrip(n), "boundary-length LFN '$n' (len=${n.length}) must round-trip")
        }
    }
}
