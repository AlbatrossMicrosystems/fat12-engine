package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Unit tests for [UndoLog] (pre-image journal) and the [readBytes] /
 * [writeBytes] block-aligned helpers.
 *
 * The undo-log is the atomicity primitive proven end-to-end by the writeFile
 * rollback tests in [Fat12VolumeTest]; these tests pin its three invariants in
 * isolation:
 *   - capture-once-per-offset (first pre-image wins),
 *   - rollback restores every captured sector byte-for-byte,
 *   - commit discards the journal without writing.
 */
class UndoLogTest {

    private fun device(blockSize: Int = 512, blocks: Long = 16L): InMemoryBlockDevice =
        InMemoryBlockDevice(blockSize = blockSize, blocks = blocks).also { dev ->
            // Seed each sector with a recognizable per-sector pattern.
            for (i in dev.bytes.indices) dev.bytes[i] = (i and 0xFF).toByte()
        }

    private fun fill(device: InMemoryBlockDevice, offset: Long, value: Byte, len: Int) {
        val buf = ByteBuffer.wrap(ByteArray(len) { value })
        device.write(offset, buf)
    }

    @Test
    fun captureIfAbsent_onlyRecordsFirstPreImagePerOffset() {
        val dev = device()
        val log = UndoLog(dev)
        val sectorOffset = 512L
        val sectorSize = 512

        // First capture: snapshot the original sector content.
        val original = readBytes(dev, sectorOffset, sectorSize)
        log.captureIfAbsent(sectorOffset, sectorSize)

        // Mutate the sector, then capture AGAIN for the same offset.
        fill(dev, sectorOffset, 0x7E, sectorSize)
        log.captureIfAbsent(sectorOffset, sectorSize)  // must be a no-op (offset already captured)

        // Mutate once more so the stored pre-image (if it were the second capture)
        // would differ from both the original and the current bytes.
        fill(dev, sectorOffset, 0x11, sectorSize)

        // Rollback must restore the ORIGINAL bytes, proving the first capture won.
        log.rollback()
        assertArrayEquals(
            original,
            readBytes(dev, sectorOffset, sectorSize),
            "captureIfAbsent must keep the FIRST pre-image; a later mutation must not replace it",
        )
    }

    @Test
    fun rollback_restoresAllCapturedSectors() {
        val dev = device()
        val log = UndoLog(dev)
        val sectorSize = 512
        val offsets = listOf(0L, 512L, 1024L, 4096L)

        val before = dev.bytes.copyOf()
        // Capture each sector before mutating it.
        for (off in offsets) {
            log.captureIfAbsent(off, sectorSize)
            fill(dev, off, 0xAB.toByte(), sectorSize)
        }
        // Sanity: device is now different from its pre-op state.
        org.junit.jupiter.api.Assertions.assertFalse(
            before.contentEquals(dev.bytes),
            "precondition: mutations must have changed the device",
        )

        log.rollback()
        assertArrayEquals(before, dev.bytes, "rollback must restore every captured sector byte-for-byte")
    }

    @Test
    fun commit_clearsWithoutWriting() {
        val dev = device()
        val log = UndoLog(dev)
        val sectorSize = 512
        val sectorOffset = 1024L

        log.captureIfAbsent(sectorOffset, sectorSize)
        fill(dev, sectorOffset, 0x5A, sectorSize)
        val afterMutation = dev.bytes.copyOf()

        // commit discards the journal; the mutation stays.
        log.commit()
        assertArrayEquals(afterMutation, dev.bytes, "commit must NOT write anything back")

        // A subsequent rollback (empty journal) must also be a no-op.
        log.rollback()
        assertArrayEquals(afterMutation, dev.bytes, "rollback after commit must do nothing (journal cleared)")
    }

    @Test
    fun readBytes_padsSubBlockRead() {
        val dev = device(blockSize = 512)
        // Read a sub-block length (100 bytes) starting at offset 0.
        val result = readBytes(dev, 0L, 100)
        assertEquals(100, result.size, "readBytes must return exactly the requested length")
        // The padded device read must have covered a whole 512-byte sector.
        val reads = dev.callLog.filterIsInstance<InMemoryBlockDevice.CallEntry.Read>()
        assertEquals(512, reads.last().length, "readBytes must pad the device read up to one full block")
        // Returned bytes match the underlying sector prefix.
        val expected = ByteArray(100) { (it and 0xFF).toByte() }
        assertArrayEquals(expected, result)
    }

    @Test
    fun writeBytes_subBlockWrite_preservesRestOfBlock() {
        // Regression: a sub-block write
        // must NOT zero-pad the trailing bytes of the sector onto disk — it must
        // read-modify-write, preserving every byte outside the written range. (The old
        // contract zeroed bytes 100..511; on 4Kn media that zero-pad landed on FAT #0.)
        val dev = device(blockSize = 512)
        // Write a sub-block length (100 bytes) of 0x33 at offset 0.
        val payload = ByteArray(100) { 0x33 }
        writeBytes(dev, 0L, payload)

        // The device write must still cover a whole 512-byte sector (block-aligned I/O).
        val writes = dev.callLog.filterIsInstance<InMemoryBlockDevice.CallEntry.Write>()
        assertEquals(512, writes.last().length, "writeBytes must transfer a whole 512-byte block")

        val sector = readBytes(dev, 0L, 512)
        // The first 100 bytes are the payload, written verbatim.
        assertArrayEquals(payload, sector.copyOfRange(0, 100), "payload bytes must be written verbatim")
        // The remaining 412 bytes of the sector must keep their PRE-EXISTING content
        // (the seeded per-byte pattern), NOT be overwritten with zeros.
        val preserved = ByteArray(412) { ((100 + it) and 0xFF).toByte() }
        assertArrayEquals(
            preserved,
            sector.copyOfRange(100, 512),
            "bytes beyond the written range must be preserved (read-modify-write), not zero-padded",
        )
    }
}
