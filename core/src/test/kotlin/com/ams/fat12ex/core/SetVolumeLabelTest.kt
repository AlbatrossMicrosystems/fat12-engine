package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [Fat12Volume.setVolumeLabel] tests — the dual-region label write under the undo log.
 *
 * Proves the both-region label rewrite (BS_VolLab @ 0x2B + the root ATTR_VOLUME_ID
 * directory entry), the create-when-absent path, the in-place update path, the
 * INT-02 byte-for-byte rollback on a mid-write fault, validation rejection (no
 * bytes touched), the listing-exclusion of the vol-ID entry, and the W3 / RESEARCH
 * Q1 empty-label-clears-to-spaces behaviour (empty is VALID, NOT InvalidName).
 *
 * Mirrors the rollback-test idiom from Fat12VolumeOpsTest (Fat12ImageBuilder().build()
 * -> failNextWriteAt -> assertThrows<FatWriteFailedException> -> assertArrayEquals).
 */
class SetVolumeLabelTest {

    private val ATTR_VOLUME_ID = 0x08
    private val BS_VOLLAB_OFFSET = 0x2B
    private val VOL_LAB_LEN = 11

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    /** The 11-byte BS_VolLab region from sector 0 (US_ASCII). */
    private fun bsVolLab(device: InMemoryBlockDevice): ByteArray {
        val sector = readBytes(device, 0L, Bpb.parse(device).bytsPerSec)
        return sector.copyOfRange(BS_VOLLAB_OFFSET, BS_VOLLAB_OFFSET + VOL_LAB_LEN)
    }

    /** The 11 name bytes of the root ATTR_VOLUME_ID entry, or null when none exists. */
    private fun rootVolIdNameBytes(device: InMemoryBlockDevice): ByteArray? {
        val bpb = Bpb.parse(device)
        val root = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        var slot = 0
        while (slot + 32 <= root.size) {
            val first = root[slot].toInt() and 0xFF
            if (first == 0x00) break // end-of-directory sentinel
            if (first != 0xE5 && (root[slot + 11].toInt() and 0xFF) == ATTR_VOLUME_ID) {
                return root.copyOfRange(slot, slot + VOL_LAB_LEN)
            }
            slot += 32
        }
        return null
    }

    /** The full 32-byte root ATTR_VOLUME_ID slot (attr/cluster/size included), or null. */
    private fun rootVolIdSlot(device: InMemoryBlockDevice): ByteArray? {
        val bpb = Bpb.parse(device)
        val root = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        var slot = 0
        while (slot + 32 <= root.size) {
            val first = root[slot].toInt() and 0xFF
            if (first == 0x00) break
            if (first != 0xE5 && (root[slot + 11].toInt() and 0xFF) == ATTR_VOLUME_ID) {
                return root.copyOfRange(slot, slot + 32)
            }
            slot += 32
        }
        return null
    }

    private fun padded(label: String): ByteArray =
        label.uppercase().padEnd(VOL_LAB_LEN, ' ').take(VOL_LAB_LEN).toByteArray(Charsets.US_ASCII)

    // ----- case 1: both regions written --------------------------------------

    @Test
    fun setVolumeLabel_writesBothRegions() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("NEWLABEL"))

        val expected = padded("NEWLABEL")
        assertArrayEquals(expected, bsVolLab(device), "BS_VolLab @ 0x2B must hold the padded label")
        val volIdName = rootVolIdNameBytes(device)
        assertNotNull(volIdName, "a root ATTR_VOLUME_ID entry must exist after setVolumeLabel")
        assertArrayEquals(expected, volIdName, "the root vol-ID entry name must hold the padded label")
    }

    // ----- case 2: create the vol-ID entry when absent -----------------------

    @Test
    fun setVolumeLabel_createsVolIdEntry_whenAbsent() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        // If the golden image already seeds a vol-ID entry, mark its slot deleted (0xE5)
        // so the create path is genuinely exercised.
        val bpb = Bpb.parse(device)
        val root = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        var slot = 0
        while (slot + 32 <= root.size) {
            val first = root[slot].toInt() and 0xFF
            if (first != 0x00 && first != 0xE5 && (root[slot + 11].toInt() and 0xFF) == ATTR_VOLUME_ID) {
                root[slot] = 0xE5.toByte()
                writeBytes(device, bpb.rootDirOffset, root)
                break
            }
            if (first == 0x00) break
            slot += 32
        }
        assertNull(rootVolIdNameBytes(device), "precondition: no vol-ID entry before the call")

        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("MADELABEL"))

        val createdSlot = rootVolIdSlot(device)
        assertNotNull(createdSlot, "a new ATTR_VOLUME_ID entry must be created in a free slot")
        assertArrayEquals(padded("MADELABEL"), createdSlot!!.copyOfRange(0, VOL_LAB_LEN))
        // firstCluster (0x1A..0x1C) and fileSize (0x1C..0x20) must be zero for a vol-ID entry.
        val firstCluster = (createdSlot[0x1A].toInt() and 0xFF) or ((createdSlot[0x1B].toInt() and 0xFF) shl 8)
        assertEquals(0, firstCluster, "vol-ID entry firstCluster must be 0")
        for (i in 0x1C until 0x20) {
            assertEquals(0, createdSlot[i].toInt() and 0xFF, "vol-ID entry fileSize must be 0")
        }
    }

    // ----- case 3: update the vol-ID entry in place when present -------------

    @Test
    fun setVolumeLabel_updatesVolIdEntry_inPlace_whenPresent() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        // First call creates (or updates) the entry; snapshot its non-name metadata.
        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("FIRSTNAME"))
        val before = rootVolIdSlot(device)!!
        val attrBefore = before[0x0B]
        val clusterBefore = before.copyOfRange(0x1A, 0x1C)
        val sizeBefore = before.copyOfRange(0x1C, 0x20)

        // Second call with a different label updates the name in place.
        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("SECONDNAME"))
        val after = rootVolIdSlot(device)!!

        assertArrayEquals(padded("SECONDNAME"), after.copyOfRange(0, VOL_LAB_LEN), "name bytes must change")
        assertEquals(attrBefore, after[0x0B], "attr byte must be untouched")
        assertArrayEquals(clusterBefore, after.copyOfRange(0x1A, 0x1C), "firstCluster must be untouched")
        assertArrayEquals(sizeBefore, after.copyOfRange(0x1C, 0x20), "fileSize must be untouched")
    }

    // ----- case 4: INT-02 byte-for-byte rollback on a mid-write fault --------

    @Test
    fun setVolumeLabel_midWriteFailure_rollsBackByteForByte() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        val snapshot = device.bytes.copyOf()

        // Fail the SECOND region (the root directory entry): by then region A
        // (BS_VolLab) is already written, so rollback must replay it too.
        device.failNextWriteAt(bpb.rootDirOffset)

        assertThrows<FatWriteFailedException> { vol.setVolumeLabel("NEWLABEL") }

        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: setVolumeLabel must roll back byte-for-byte on a mid-write failure",
        )
    }

    // ----- case 5: invalid name rejected, no bytes changed -------------------

    @Test
    fun setVolumeLabel_invalidName_rejected() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        val snapshot = device.bytes.copyOf()

        assertInstanceOf(Fat12Result.InvalidName::class.java, vol.setVolumeLabel("bad/name"))
        assertInstanceOf(Fat12Result.InvalidName::class.java, vol.setVolumeLabel("THISLABELISWAYTOOLONG"))

        assertArrayEquals(
            snapshot,
            device.bytes,
            "an invalid label must change no bytes",
        )
    }

    // ----- case 6: the vol-ID entry never appears in list() ------------------

    @Test
    fun setVolumeLabel_doesNotAppearInListing() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("HIDDENLBL"))

        val entries = (vol.list("") as Fat12Result.Ok).value
        assertTrue(
            entries.none { (it.attributes and ATTR_VOLUME_ID) != 0 },
            "the vol-ID entry must never appear in the directory listing",
        )
    }

    // ----- case 7: empty/blank label clears to 11 spaces, returns Ok (W3) ----

    @Test
    fun setVolumeLabel_emptyLabel_clearsToSpaces_returnsOk() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        // Start from a non-empty label.
        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("OLDLABEL"))

        // An empty submit is VALID — it clears the label to 11 spaces, returns Ok.
        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel(""))

        val spaces = "           ".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(spaces, bsVolLab(device), "empty label must clear BS_VolLab to 11 spaces")
        assertArrayEquals(spaces, rootVolIdNameBytes(device), "empty label must clear the vol-ID entry to 11 spaces")
        assertEquals("", vol.volumeLabel(), "volumeLabel() trimEnds an all-spaces label to \"\"")

        // A blank (whitespace-only) submit behaves identically.
        assertInstanceOf(Fat12Result.Ok::class.java, vol.setVolumeLabel("   "))
        assertEquals("", vol.volumeLabel(), "a blank label clears identically")
        assertFalse(vol.setVolumeLabel("ok") is Fat12Result.InvalidName, "a normal label still succeeds afterward")
    }
}
