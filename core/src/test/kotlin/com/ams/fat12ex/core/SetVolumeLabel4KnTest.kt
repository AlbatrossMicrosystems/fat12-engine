package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * End-to-end regression for the 4Kn sub-block label-write hazard.
 *
 * Reproduces the Radix 4Kn case: a 4096-byte-logical-sector device
 * (`device.blockSize == 4096`) carrying a 512-byte-sector FAT12
 * (`bytsPerSec == 512`). On this geometry the boot-sector write in
 * [Fat12Volume.setVolumeLabel] used to be zero-padded from 512 to 4096 bytes,
 * clobbering disk bytes 512..4095 = the start of FAT #0, destroying the
 * allocation (the volume still mounted but listed only its first root entry —
 * exactly the on-device symptom).
 *
 * The test builds a normal 512/512 FAT12 with two files, transplants its bytes
 * into a 4096-block device, edits the label, and asserts FAT #0 and every root
 * directory entry survive byte-for-byte.
 */
class SetVolumeLabel4KnTest {

    /**
     * Build a 512/512 FAT12 image with two files, then copy its bytes into a
     * device that reports a 4096-byte block size (4Kn LUN). The BPB still says
     * `bytsPerSec == 512`, so `device.blockSize (4096) > bytsPerSec (512)`.
     */
    private fun build4knVolumeWithTwoFiles(): InMemoryBlockDevice {
        val src = Fat12ImageBuilder()
            .withReservedShortEntry(
                name83 = "README  HTM",
                clusters = listOf(2),
                bytes = "<html>readme</html>".toByteArray(Charsets.US_ASCII),
            )
            .withReservedShortEntry(
                name83 = "DATA    BIN",
                clusters = listOf(3),
                bytes = ByteArray(4096) { 0x5A },
            )
            .build()

        val blockSize4k = 4096
        val blocks = ((src.bytes.size + blockSize4k - 1) / blockSize4k).toLong()
        val device = InMemoryBlockDevice(blockSize = blockSize4k, blocks = blocks)
        src.bytes.copyInto(device.bytes)
        return device
    }

    @Test
    fun setVolumeLabel_on4knDevice_preservesFat0AndAllRootEntries() {
        val device = build4knVolumeWithTwoFiles()

        // Sanity: this is genuinely the bug-triggering geometry.
        val bpb = Bpb.parse(device)
        assertEquals(4096, device.blockSize, "device must report a 4096-byte block")
        assertEquals(512, bpb.bytsPerSec, "the FAT12 BPB must report a 512-byte sector")

        // Snapshot the regions the bug used to destroy.
        val fat0Before = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        val rootBefore = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)

        val vol = Fat12Volume(device).apply { open() }
        // Confirm both files are listed before the edit.
        val before = (vol.list("") as Fat12Result.Ok).value.map { it.name }.toSet()

        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("NEWLABEL"))

        // FAT #0 must be untouched — the allocation must survive the label edit.
        val fat0After = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        assertArrayEquals(
            fat0Before,
            fat0After,
            "FAT #0 must survive setVolumeLabel on a 4Kn device (the boot-sector write " +
                "must not zero-pad into the FAT)",
        )

        // Both files must still be listed after the edit.
        val after = (vol.list("") as Fat12Result.Ok).value.map { it.name }.toSet()
        assertEquals(before, after, "the same files must be listed before and after the label edit")

        // Spot-check: the README short-entry slot is unchanged byte-for-byte.
        val rootAfter = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        assertReadmeEntrySurvived(rootBefore, rootAfter)
    }

    private fun assertReadmeEntrySurvived(rootBefore: ByteArray, rootAfter: ByteArray) {
        val readme = "README  HTM".toByteArray(Charsets.US_ASCII)
        val slot = findEntry(rootBefore, readme)
        require(slot >= 0) { "README entry must exist in the pre-edit root" }
        assertArrayEquals(
            rootBefore.copyOfRange(slot, slot + 32),
            rootAfter.copyOfRange(slot, slot + 32),
            "the README directory entry must survive the label edit byte-for-byte",
        )
    }

    private fun findEntry(root: ByteArray, name83: ByteArray): Int {
        var off = 0
        while (off + 32 <= root.size) {
            if (root.copyOfRange(off, off + 11).contentEquals(name83)) return off
            off += 32
        }
        return -1
    }
}
