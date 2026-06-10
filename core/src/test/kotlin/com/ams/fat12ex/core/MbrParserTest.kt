package com.ams.fat12ex.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MbrParserTest {

    @Test
    fun parses_512nDisk_default_sectorSize() {
        // Standard 512-byte sector disk: MBR LBA × 512 = byte offset.
        val sector0 = mbrSector(
            entries = listOf(Triple(0x06, 504L, 7680L)),
        )
        val info = MbrParser.parseFirstPartition(sector0)
        checkNotNull(info)
        assertEquals(258048L, info.byteOffset)        // 504 * 512
        assertEquals(7680L * 512, info.byteLength)    // 3,932,160
        assertTrue(info.partitionType in setOf(0x01, 0x04, 0x06, 0x0E), "type should be FAT12/16")
    }

    @Test
    fun parses_radix4KnDisk_realMbr() {
        // Real Radix 2 HD firmware LUN (logcat 2026-05-06 01:14:20): LUN reports
        // blockSize=4096, MBR partition entry has startLba=63, sectorCount=960,
        // type=0x01 (FAT12). Wave-0 §3 confirmed the FAT12 volume is at byte
        // offset 258,048 (= 63 × 4096) and is 3,932,160 bytes (= 960 × 4096) —
        // i.e. for 4Kn disks, MBR LBA values are interpreted in the native
        // sector size, NOT a hardcoded 512.
        val sector0 = mbrSector(
            entries = listOf(Triple(0x01, 63L, 960L)),
        )
        val info = MbrParser.parseFirstPartition(sector0, sectorSize = 4096)
        checkNotNull(info)
        assertEquals(258048L, info.byteOffset)        // 63 * 4096 — matches Wave-0
        assertEquals(3_932_160L, info.byteLength)     // 960 * 4096 — matches Wave-0
        assertEquals(0x01, info.partitionType)
    }

    @Test
    fun noBootSig_returnsNull() {
        val sector0 = mbrSector(
            bootSig = false,
            entries = listOf(Triple(0x06, 504L, 7680L)),
        )
        assertNull(MbrParser.parseFirstPartition(sector0))
    }

    @Test
    fun allEmpty_returnsNull() {
        val sector0 = mbrSector(entries = emptyList())
        assertNull(MbrParser.parseFirstPartition(sector0))
    }

    @Test
    fun firstEmptySecondPresent_returnsSecond() {
        // entry 0 has type=0 (empty) — should be skipped; entry 1 returned.
        val sector0 = ByteArray(512).also {
            it[510] = 0x55.toByte()
            it[511] = 0xAA.toByte()
            // entry 0: type=0 (empty) — leave bytes zero; explicitly write 0 for clarity
            it[0x1BE + 4] = 0
            // entry 1: type=0x0B (FAT32), startLba=2048, sectorCount=100
            it[0x1CE + 4] = 0x0B
            // startLba LE u32 at +8
            it[0x1CE + 8] = (2048 and 0xFF).toByte()
            it[0x1CE + 9] = ((2048 shr 8) and 0xFF).toByte()
            it[0x1CE + 10] = 0
            it[0x1CE + 11] = 0
            // sectorCount LE u32 at +12
            it[0x1CE + 12] = 100
            it[0x1CE + 13] = 0
            it[0x1CE + 14] = 0
            it[0x1CE + 15] = 0
        }
        val info = MbrParser.parseFirstPartition(sector0)
        checkNotNull(info)
        assertEquals(2048L * 512, info.byteOffset)
        assertEquals(100L * 512, info.byteLength)
        assertEquals(0x0B, info.partitionType)
    }

    @Test
    fun tooShort_throwsIAE() {
        assertThrows(IllegalArgumentException::class.java) {
            MbrParser.parseFirstPartition(ByteArray(511))
        }
    }

    @Test
    fun largeStartLba_doesNotSignExtend() {
        // startLba = 0x80000000 (high bit set as unsigned u32 = 2^31).
        // Without unsigned-correct readLeU32, this would sign-extend to negative
        // and produce a huge negative byteOffset. With correct semantics, byteOffset
        // = 0x80000000 * 512 = 1,099,511,627,776 (1 TiB, positive).
        val sector0 = ByteArray(512).also {
            it[510] = 0x55.toByte()
            it[511] = 0xAA.toByte()
            it[0x1BE + 4] = 0x07  // NTFS, arbitrary non-zero
            it[0x1BE + 8] = 0x00
            it[0x1BE + 9] = 0x00
            it[0x1BE + 10] = 0x00
            it[0x1BE + 11] = 0x80.toByte()  // high bit of u32
            it[0x1BE + 12] = 0x10
            it[0x1BE + 13] = 0x00
            it[0x1BE + 14] = 0x00
            it[0x1BE + 15] = 0x00
        }
        val info = MbrParser.parseFirstPartition(sector0)
        checkNotNull(info)
        assertEquals(0x80000000L * 512L, info.byteOffset)
        assertTrue(info.byteOffset > 0, "byteOffset must be positive")
    }

    private fun mbrSector(
        bootSig: Boolean = true,
        entries: List<Triple<Int, Long, Long>> = emptyList(),
    ): ByteArray {
        val buf = ByteArray(512)
        if (bootSig) {
            buf[510] = 0x55.toByte()
            buf[511] = 0xAA.toByte()
        }
        entries.forEachIndexed { i, (type, startLba, count) ->
            if (i >= 4) return@forEachIndexed
            val off = 0x1BE + i * 16
            buf[off + 4] = type.toByte()
            buf[off + 8] = (startLba and 0xFF).toByte()
            buf[off + 9] = ((startLba shr 8) and 0xFF).toByte()
            buf[off + 10] = ((startLba shr 16) and 0xFF).toByte()
            buf[off + 11] = ((startLba shr 24) and 0xFF).toByte()
            buf[off + 12] = (count and 0xFF).toByte()
            buf[off + 13] = ((count shr 8) and 0xFF).toByte()
            buf[off + 14] = ((count shr 16) and 0xFF).toByte()
            buf[off + 15] = ((count shr 24) and 0xFF).toByte()
        }
        return buf
    }
}
