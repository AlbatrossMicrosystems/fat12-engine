package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [Fat12Volume] facade tests — open/list/readFile/writeFile/volume-info plus the
 * two INT-02 rollback proofs (mid-write failure + verify mismatch). The rollback
 * proofs snapshot `device.bytes` before the op and assert byte-for-byte identity
 * afterward (Success Criterion #5).
 */
class Fat12VolumeTest {

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    // ----- open --------------------------------------------------------------

    @Test
    fun open_validVolume_ok() {
        val device = Fat12ImageBuilder().build()
        val result = Fat12Volume(device).open()
        assertInstanceOf(Fat12Result.Ok::class.java, result)
    }

    @Test
    fun open_nonFat12_throwsNotFat12() {
        // A device whose BPB reports a FAT16/FAT32-sized cluster count must be rejected.
        val device = Fat12ImageBuilder(clusterCount = 5000, fsType = "FAT16   ").build()
        assertThrows<NotFat12Exception> { Fat12Volume(device).open() }
    }

    // ----- volume info -------------------------------------------------------

    @Test
    fun volumeInfo_consistentWithBpb() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        assertEquals(bpb.clusterSize, vol.clusterSize(), "clusterSize must equal bpb.clusterSize")
        assertEquals(bpb.clusterCount.toLong() * bpb.clusterSize, vol.totalBytes(), "totalBytes")

        // freeBytes derives from FAT[0] free-cluster count.
        val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        val expectedFree = FatTable.countFreeClusters(fat, bpb.clusterCount).toLong() * bpb.clusterSize
        assertEquals(expectedFree, vol.freeBytes(), "freeBytes")
    }

    // ----- list --------------------------------------------------------------

    @Test
    fun list_root_returnsEntries() {
        val device = Fat12ImageBuilder()
            .withReservedShortEntry("README  TXT", attr = 0x20, clusters = listOf(2), bytes = ByteArray(100) { 'x'.code.toByte() })
            .build()
        val vol = openVolume(device)

        val result = vol.list("/")
        assertInstanceOf(Fat12Result.Ok::class.java, result)
        val entries = (result as Fat12Result.Ok).value
        assertTrue(entries.any { it.shortName == "README  TXT" }, "list must contain README.TXT short entry")
        val readme = entries.first { it.shortName == "README  TXT" }
        assertEquals("README.TXT", readme.name, "8.3 name decoded for display")
        assertEquals(100L, readme.size)
        assertFalse(readme.isDirectory)
    }

    // ----- readFile ----------------------------------------------------------

    @Test
    fun readFile_roundTrips() {
        val content = ByteArray(3000) { (it and 0x7F).toByte() }
        val device = Fat12ImageBuilder()
            .withReservedShortEntry("DATA    BIN", attr = 0x20, clusters = listOf(2), bytes = content)
            .build()
        val vol = openVolume(device)

        val result = vol.readFile("/DATA.BIN")
        assertInstanceOf(Fat12Result.Ok::class.java, result)
        assertArrayEquals(content, (result as Fat12Result.Ok).value, "readFile must return the exact file bytes")
    }

    // ----- writeFile success -------------------------------------------------

    @Test
    fun writeFile_success_fileReadsBackIdentical_andProgressCalled() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val cs = vol.clusterSize()

        // Two-cluster payload split across multiple chunks (exercises streaming).
        val payload = ByteArray(cs + cs / 2) { (it and 0xFF).toByte() }
        val chunks = sequenceOf(
            payload.copyOfRange(0, cs / 2),
            payload.copyOfRange(cs / 2, cs),
            payload.copyOfRange(cs, payload.size),
        )

        val progress = mutableListOf<Pair<Long, Long>>()
        val result = vol.writeFile("/OUT.BIN", chunks) { written, total -> progress.add(written to total) }
        assertInstanceOf(Fat12Result.Ok::class.java, result)

        // File reads back identical.
        val readBack = vol.readFile("/OUT.BIN")
        assertInstanceOf(Fat12Result.Ok::class.java, readBack)
        assertArrayEquals(payload, (readBack as Fat12Result.Ok).value, "written file must read back identical")

        // Progress was reported and the final callback equals (total, total).
        assertTrue(progress.isNotEmpty(), "onProgress must be invoked at least once")
        assertEquals(payload.size.toLong(), progress.last().first, "final progress bytesWritten == total")
        assertEquals(payload.size.toLong(), progress.last().second, "progress total == payload size")

        // The new entry appears in the listing.
        val entries = (vol.list("/") as Fat12Result.Ok).value
        assertTrue(entries.any { it.shortName == "OUT     BIN" }, "new file must be listed")
    }

    // ----- INT-02 proof #1: mid-write failure --------------------------------

    @Test
    fun writeFile_midWriteFailure_rollsBackToPreOpState() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)
        val cs = vol.clusterSize()

        // Snapshot the entire volume BEFORE the op.
        val snapshot = device.bytes.copyOf()

        // Inject a write failure on the SECOND data cluster (mid-file): the allocator
        // picks clusters 2,3 for a 2-cluster write; fail the write to cluster 3.
        val secondClusterOffset = bpb.byteOffsetOfCluster(3)
        device.failNextWriteAt(secondClusterOffset)

        // 2-cluster payload so the failure lands mid-write (after cluster 2, on cluster 3).
        val payload = ByteArray(2 * cs) { (it and 0xFF).toByte() }

        // The write must surface failure (thrown FatWriteFailedException).
        val ex = assertThrows<FatWriteFailedException> {
            vol.writeFile("/BIG.BIN", sequenceOf(payload))
        }
        assertTrue(ex.cause != null, "FatWriteFailedException must wrap the underlying IOException")

        // INT-02: the volume must be byte-for-byte identical to its pre-op state.
        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: volume must be byte-for-byte identical to pre-op state after mid-write rollback",
        )
        // And no directory entry leaked.
        val entries = (vol.list("/") as Fat12Result.Ok).value
        assertTrue(entries.none { it.shortName == "BIG     BIN" }, "no dir entry may be committed after rollback")
    }

    // ----- INT-02 proof #2: verify mismatch ----------------------------------

    @Test
    fun writeFile_verifyMismatch_rollsBackAndNoDirEntry() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)
        val cs = vol.clusterSize()

        val snapshot = device.bytes.copyOf()

        // Corrupt the read-back of a just-written cluster so the verify pass detects a
        // mismatch (the device acknowledges the write but returns wrong bytes on read).
        //
        // HR-01 (01-09) note on WHEN the fault is armed: rollback now captures each
        // data cluster's TRUE pre-image with a device read BEFORE writing it (so a
        // cluster reused from a prior delete is restored byte-for-byte, not zeroed).
        // That pre-image capture happens up front for the whole chain, before any
        // write/verify. The InMemoryBlockDevice corrupt-on-read fault is one-shot, so
        // arming it before the call would be consumed by the (pre-write) capture read,
        // not the (post-write) verify read. We therefore arm it AFTER the capture pass
        // has completed — from inside onProgress, once the FIRST cluster is verified —
        // so the one-shot lands on the SECOND cluster's verify read-back, exactly the
        // fault this proof exists to catch. The harness is untouched; the proof's
        // intent (verify read-back returns wrong bytes -> byte-for-byte rollback) is
        // unchanged — the corruption still strikes a post-write verify read.
        val secondClusterOffset = bpb.byteOffsetOfCluster(3)
        val payload = ByteArray(2 * cs) { (it and 0xFF).toByte() }

        var armed = false
        assertThrows<FatVerifyFailedException> {
            vol.writeFile("/V.BIN", sequenceOf(payload)) { _, _ ->
                // After the first cluster is written + verified (and the whole chain's
                // pre-images are already captured), arm the one-shot read corruption on
                // the SECOND cluster so it fires on that cluster's verify read-back.
                if (!armed) {
                    device.corruptOnReadAt(secondClusterOffset, mask = 0xFF.toByte())
                    armed = true
                }
            }
        }

        // INT-02: byte-for-byte identical to pre-op state.
        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: volume must be byte-for-byte identical to pre-op state after verify-mismatch rollback",
        )
        // No directory entry committed.
        val entries = (vol.list("/") as Fat12Result.Ok).value
        assertTrue(entries.none { it.shortName == "V       BIN" }, "no dir entry may be committed on verify mismatch")
    }
}
