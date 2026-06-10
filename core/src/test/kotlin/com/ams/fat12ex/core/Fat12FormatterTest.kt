package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.GoldenImages
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Geometry + structure tests for [Fat12Formatter] and the mkfs.fat
 * golden cross-validation.
 *
 * The geometry tests prove the `< 4069` boundary, TooLarge rejection,
 * FAT reserved-entry + dual-FAT-sync invariants, and the boot
 * signatures, all read back through the production [Bpb] parser.
 */
class Fat12FormatterTest {

    private fun u8(b: ByteArray, off: Int) = b[off].toInt() and 0xFF

    // ---- Task 1: geometry + structure --------------------------------------

    @Test
    fun format_1440k_producesValidFat12Geometry() {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L)

        val result = Fat12Formatter(device).format()
        assertTrue(result is Fat12Result.Ok, "expected Ok, got $result")

        val bpb = Bpb.parse(device)
        assertTrue(
            bpb.clusterCount in 1..4068,
            "clusterCount ${bpb.clusterCount} must be in 1..4068 (< 4069)",
        )
        assertTrue(bpb.clusterCount < 4069, "FAT12 boundary breached: ${bpb.clusterCount}")
    }

    @Test
    fun format_writesBootSignatureAndBsBootSig() {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L)
        Fat12Formatter(device).format()

        val b = device.bytes
        assertEquals(0x55, u8(b, 510), "boot signature byte 510 must be 0x55")
        assertEquals(0xAA, u8(b, 511), "boot signature byte 511 must be 0xAA")
        assertEquals(0x29, u8(b, 38), "BS_BootSig at offset 38 must be 0x29")
    }

    @Test
    fun format_dualFatsAreByteForByteIdentical() {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L)
        Fat12Formatter(device).format()

        val bpb = Bpb.parse(device)
        val b = device.bytes
        val fat0 = b.copyOfRange(bpb.fat0Offset.toInt(), bpb.fat0Offset.toInt() + bpb.fatBytes)
        val fat1 = b.copyOfRange(bpb.fat1Offset.toInt(), bpb.fat1Offset.toInt() + bpb.fatBytes)
        assertTrue(fat0.contentEquals(fat1), "dual FATs must be byte-for-byte identical")
    }

    @Test
    fun format_fatReservedEntries_FF8_FFF() {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L)
        // Default media descriptor 0xF8 -> FAT[0] = 0xFF8, FAT[1] = 0xFFF.
        Fat12Formatter(device).format()

        val bpb = Bpb.parse(device)
        val fat = device.bytes.copyOfRange(bpb.fat0Offset.toInt(), bpb.fat0Offset.toInt() + bpb.fatBytes)
        assertEquals(0xFF8, FatTable.read12(fat, 0), "FAT[0] reserved entry must be 0xFF8 (media F8 mirror)")
        assertEquals(0xFFF, FatTable.read12(fat, 1), "FAT[1] reserved entry must be 0xFFF (EOC)")
    }

    @Test
    fun format_oversizeDevice_returnsTooLarge() {
        // 16,777,216 sectors far exceeds the FAT12 addressable max for any spc.
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 16_777_216L)
        val result = Fat12Formatter(device).format()
        assertTrue(result is Fat12Result.TooLarge, "oversize device must return TooLarge, got $result")
    }

    @Test
    fun computeGeometry_picksSmallestSpcThatFits() {
        val formatter = Fat12Formatter(InMemoryBlockDevice(blockSize = 512, blocks = 2880L))

        // 1440K (2880 sectors): smallest spc=1 already yields clusters < 4069.
        val g1440 = formatter.computeGeometry(2880, 512)
        requireNotNull(g1440) { "1440K geometry must exist" }
        assertEquals(1, g1440.secPerClus, "1440K must pick spc=1 (smallest that fits)")
        assertTrue(g1440.clusters in 1 until 4069, "1440K clusters ${g1440.clusters} out of range")

        // A device whose spc=1 would exceed 4068 clusters must escalate to a
        // larger spc. ~8192 sectors at spc=1 yields ~8175 clusters (> 4068),
        // forcing spc=2.
        val gLarge = formatter.computeGeometry(8192, 512)
        requireNotNull(gLarge) { "8192-sector geometry must exist" }
        assertTrue(gLarge.secPerClus >= 2, "8192 sectors must escalate beyond spc=1, got ${gLarge.secPerClus}")
        assertTrue(gLarge.clusters in 1 until 4069, "8192 clusters ${gLarge.clusters} out of range")
    }

    // ---- Golden cross-validation against mkfs.fat --------------------------

    /**
     * Cross-validate the engine's format output against the committed mkfs.fat
     * 1440K golden fixture byte-for-byte across the GEOMETRY-DEFINING BPB
     * fields and BOTH FAT regions.
     *
     * The FULL 512-byte
     * BPB sector is NOT compared: mkfs.fat writes tool-specific/volatile bytes
     * the engine does not reproduce (OEM "mkfs.fat" at 3..10, random VolID at
     * 39..42, drive-geometry hints secPerTrk/numHeads at 24..27, and a 128-byte
     * bootstrap stub at 62..509). Those are cosmetic and excluded. The geometry
     * fields that DEFINE the filesystem AND the FAT allocation regions ARE
     * compared exactly — that is where a real geometry/structure divergence
     * (the corruption-prone geometry surface) would surface as a defect.
     *
     * To make the FAT regions align, the test passes the floppy media descriptor
     * (0xF0) and the matching volume label so the engine emits the same spec
     * geometry mkfs.fat chose for a 1440K floppy (spc=1, rootEnt=224, fatSz=9,
     * 2847 clusters), which equals the engine's own computeGeometry result.
     */
    @Test
    fun format_1440k_matchesGoldenFixture_bpbAndFatRegions() {
        val golden = GoldenImages.load1440k()

        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L)
        val result = Fat12Formatter(device).format(
            volumeLabel = "TEST1440K  ",
            volumeId = readU32(golden, 39), // match golden VolID so 39..42 align too
            mediaDescriptor = 0xF0,         // 1440K floppy media byte (FAT spec)
        )
        assertTrue(result is Fat12Result.Ok, "format must succeed, got $result")

        val mine = device.bytes

        // 1) Geometry-defining BPB fields (compared exactly; excludes cosmetic bytes).
        assertEquals(readU16(golden, 0x0B), readU16(mine, 0x0B), "bytsPerSec (0x0B) divergence")
        assertEquals(readU8(golden, 0x0D), readU8(mine, 0x0D), "secPerClus (0x0D) divergence")
        assertEquals(readU16(golden, 0x0E), readU16(mine, 0x0E), "rsvdSecCnt (0x0E) divergence")
        assertEquals(readU8(golden, 0x10), readU8(mine, 0x10), "numFATs (0x10) divergence")
        assertEquals(readU16(golden, 0x11), readU16(mine, 0x11), "rootEntCnt (0x11) divergence")
        assertEquals(readU16(golden, 0x13), readU16(mine, 0x13), "totSec16 (0x13) divergence")
        assertEquals(readU8(golden, 0x15), readU8(mine, 0x15), "media descriptor (0x15) divergence")
        assertEquals(readU16(golden, 0x16), readU16(mine, 0x16), "fatSz16 (0x16) divergence")
        assertEquals(readU8(golden, 38), readU8(mine, 38), "BS_BootSig (0x26=38) divergence")
        assertArrayEquals(
            golden.copyOfRange(0x36, 0x3E), mine.copyOfRange(0x36, 0x3E),
            "FilSysType label (0x36..0x3D) divergence",
        )
        assertEquals(0x55, readU8(mine, 510), "boot sig 510")
        assertEquals(0xAA, readU8(mine, 511), "boot sig 511")
        assertArrayEquals(
            golden.copyOfRange(510, 512), mine.copyOfRange(510, 512),
            "boot signature 510..511 divergence",
        )

        // 2) FAT regions byte-for-byte, offsets derived from the parsed BPB
        //    (geometry-driven, not hardcoded), proving the allocation tables
        //    (reserved entries + free-cluster layout) match exactly.
        val bpb = Bpb.parse(device)
        val fat0 = bpb.fat0Offset.toInt()
        val fat1 = bpb.fat1Offset.toInt()
        val fatLen = bpb.fatBytes
        assertArrayEquals(
            golden.copyOfRange(fat0, fat0 + fatLen),
            mine.copyOfRange(fat0, fat0 + fatLen),
            "FAT[0] region divergence (offset $fat0, len $fatLen)",
        )
        assertArrayEquals(
            golden.copyOfRange(fat1, fat1 + fatLen),
            mine.copyOfRange(fat1, fat1 + fatLen),
            "FAT[1] region divergence (offset $fat1, len $fatLen)",
        )
    }

    private fun readU8(b: ByteArray, off: Int) = b[off].toInt() and 0xFF
    private fun readU16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun readU32(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
