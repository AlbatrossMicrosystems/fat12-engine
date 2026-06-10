package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression guard for the sub-block read-modify-write hazard (a 512-byte write on a
 * 4096-byte-logical-sector device must not clobber the enclosing block).
 *
 * The original [writeBytes] zero-padded any sub-block-length write up to
 * `device.blockSize` and wrote those zeros to disk. On 4Kn media — a device whose
 * `blockSize` (4096) exceeds the FAT12's `bytsPerSec` (512) — a 512-byte boot-
 * sector write at offset 0 was padded to 4096 and clobbered disk bytes 512..4095,
 * i.e. the START of FAT #0. These tests assert the helpers are alignment-safe:
 * every byte outside the written range is preserved, and every device transfer is
 * whole-block and block-aligned (so the device-layer LBA division never truncates).
 */
class BlockDeviceIoAlignmentTest {

    // ----- the exact corruption case -----------------------------------------

    @Test
    fun writeBytes_subBlockWriteAt0_preservesRestOfBlock_on4kDevice() {
        val device = InMemoryBlockDevice(blockSize = 4096, blocks = 4L)
        // Pre-fill the first block with a sentinel so any zero-pad would be visible.
        for (i in 0 until 4096) device.bytes[i] = 0xCD.toByte()

        // Write a 512-byte "boot sector" at offset 0 (exactly what setVolumeLabel does).
        writeBytes(device, 0L, ByteArray(512) { 0xAB.toByte() })

        // Bytes 0..511 hold the written value.
        for (i in 0 until 512) {
            assertEquals(0xAB.toByte(), device.bytes[i], "written region byte $i")
        }
        // Bytes 512..4095 — the start of FAT #0 on a real 4Kn FAT12 — MUST be untouched.
        for (i in 512 until 4096) {
            assertEquals(
                0xCD.toByte(),
                device.bytes[i],
                "byte $i (FAT #0 region) must be preserved, not zero-padded",
            )
        }
    }

    // ----- the trailing partial block of a multi-block write -----------------

    @Test
    fun writeBytes_trailingPartialBlock_preservesTail_on4kDevice() {
        val device = InMemoryBlockDevice(blockSize = 4096, blocks = 4L)
        for (i in device.bytes.indices) device.bytes[i] = 0xCD.toByte()

        // Write 5000 bytes: 1 full block + a 904-byte partial second block.
        writeBytes(device, 0L, ByteArray(5000) { 0xAB.toByte() })

        for (i in 0 until 5000) assertEquals(0xAB.toByte(), device.bytes[i], "written byte $i")
        // The rest of block 2 (5000..8191) must keep its sentinel.
        for (i in 5000 until 8192) {
            assertEquals(0xCD.toByte(), device.bytes[i], "trailing-block byte $i must be preserved")
        }
    }

    // ----- a non-block-aligned start offset ----------------------------------

    @Test
    fun writeBytes_nonBlockAlignedStart_preservesLeadingAndTrailing_on4kDevice() {
        val device = InMemoryBlockDevice(blockSize = 4096, blocks = 4L)
        for (i in device.bytes.indices) device.bytes[i] = 0xCD.toByte()

        // Write 512 bytes starting at byte 512 (a sub-block, non-aligned region — the
        // root-dir / FAT region case on 4Kn media).
        writeBytes(device, 512L, ByteArray(512) { 0xAB.toByte() })

        for (i in 0 until 512) assertEquals(0xCD.toByte(), device.bytes[i], "leading byte $i preserved")
        for (i in 512 until 1024) assertEquals(0xAB.toByte(), device.bytes[i], "written byte $i")
        for (i in 1024 until 4096) assertEquals(0xCD.toByte(), device.bytes[i], "trailing byte $i preserved")
    }

    // ----- every device transfer is whole-block and block-aligned ------------

    @Test
    fun writeBytes_issuesOnlyWholeBlockAlignedTransfers_on4kDevice() {
        val device = InMemoryBlockDevice(blockSize = 4096, blocks = 4L)
        writeBytes(device, 512L, ByteArray(512) { 0xAB.toByte() })

        val writes = device.callLog.filterIsInstance<InMemoryBlockDevice.CallEntry.Write>()
        assertTrue(writes.isNotEmpty(), "a write must have been issued")
        for (w in writes) {
            assertEquals(0L, w.offset % 4096, "write offset ${w.offset} must be block-aligned")
            assertEquals(0, w.length % 4096, "write length ${w.length} must be a whole number of blocks")
        }
    }

    // ----- read round-trips the same window ----------------------------------

    @Test
    fun readBytes_returnsExactRange_on4kDevice() {
        val device = InMemoryBlockDevice(blockSize = 4096, blocks = 4L)
        for (i in device.bytes.indices) device.bytes[i] = (i and 0xFF).toByte()

        val read = readBytes(device, 512L, 512)
        val expected = ByteArray(512) { ((512 + it) and 0xFF).toByte() }
        assertArrayEquals(expected, read, "readBytes must return exactly the requested byte range")
    }

    // ----- on a matched 512/512 device the behaviour is unchanged ------------

    @Test
    fun writeBytes_on512Device_unchangedBehaviour() {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 8L)
        for (i in device.bytes.indices) device.bytes[i] = 0xCD.toByte()

        writeBytes(device, 0L, ByteArray(512) { 0xAB.toByte() })

        for (i in 0 until 512) assertEquals(0xAB.toByte(), device.bytes[i], "written byte $i")
        for (i in 512 until device.bytes.size) {
            assertEquals(0xCD.toByte(), device.bytes[i], "byte $i beyond the write preserved")
        }
    }
}
