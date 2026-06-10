package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [Fat12Volume.setAttributes] tests — the attribute-byte rewrite under the undo log.
 *
 * `setAttributes(path, attrs)` rewrites ONLY the user-settable attribute bits
 * (R=0x01 / H=0x02 / S=0x04 / A=0x20) of a directory entry's attr byte at
 * `shortEntryOffset + 0x0B`, under the INT-02 verify-after-write + rollback
 * contract (modelled verbatim on [Fat12Volume.setVolumeLabel]). The non-user
 * bits `ATTR_VOLUME_ID` (0x08) and `ATTR_DIRECTORY` (0x10) are PRESERVED from
 * the existing byte (Pitfall 4) — a user can never flip a file into a folder
 * or clear the directory bit.
 *
 * Proves: writes only user bits; preserves VOLUME_ID/DIRECTORY; a folder stays
 * a folder; byte-for-byte rollback on a mid-write fault (INT-02); persistence
 * across a close/re-open (SC#1); NotFound for a missing entry or parent (no
 * bytes touched); and a no-op (re-applying the current attrs) is idempotent.
 *
 * Mirrors the rollback-test idiom from [SetVolumeLabelTest] (Fat12ImageBuilder
 * -> failNextWriteAt -> assertThrows<FatWriteFailedException> -> assertArrayEquals).
 */
class SetAttributesTest {

    private val ATTR_READ_ONLY = 0x01
    private val ATTR_HIDDEN = 0x02
    private val ATTR_SYSTEM = 0x04
    private val ATTR_VOLUME_ID = 0x08
    private val ATTR_DIRECTORY = 0x10
    private val ATTR_ARCHIVE = 0x20

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    /**
     * Build an image with a plain data file `DATA.BIN` (attr=ARCHIVE) at cluster 2,
     * plus a subdirectory `SUBDIR` (attr=DIRECTORY) at cluster 3.
     */
    private fun imageWithFileAndDir(): InMemoryBlockDevice =
        Fat12ImageBuilder()
            .withReservedShortEntry(
                name83 = "DATA    BIN",
                attr = ATTR_ARCHIVE,
                clusters = listOf(2),
                bytes = ByteArray(16) { it.toByte() },
            )
            .withReservedShortEntry(
                name83 = "SUBDIR     ",
                attr = ATTR_DIRECTORY,
                clusters = listOf(3),
                bytes = ByteArray(16),
            )
            .build()

    /**
     * Raw-peek the attribute byte (offset 0x0B) of the short entry whose dotted
     * display name equals [displayName], by re-reading the ROOT dir region from
     * the device directly (no engine helpers — proves the on-disk byte).
     */
    private fun rootAttrByte(device: InMemoryBlockDevice, displayName: String): Int {
        val bpb = Bpb.parse(device)
        val root = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        var slot = 0
        while (slot + 32 <= root.size) {
            val first = root[slot].toInt() and 0xFF
            if (first == 0x00) break
            val attr = root[slot + 0x0B].toInt() and 0xFF
            if (first != 0xE5 && attr != 0x0F) { // skip deleted + LFN preamble entries
                val name83 = String(root, slot, 11, Charsets.US_ASCII)
                val base = name83.substring(0, 8).trim()
                val ext = name83.substring(8, 11).trim()
                val dotted = if (ext.isEmpty()) base else "$base.$ext"
                if (dotted.equals(displayName, ignoreCase = true)) return attr
            }
            slot += 32
        }
        error("entry '$displayName' not found in root dir")
    }

    // ----- case 1: writes ONLY the user bits ---------------------------------

    @Test
    fun writesOnlyUserBits() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        // DATA.BIN starts at ARCHIVE (0x20). Set R|A; H and S must be clear.
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/DATA.BIN", ATTR_READ_ONLY or ATTR_ARCHIVE),
        )

        val attr = rootAttrByte(device, "DATA.BIN")
        assertEquals(ATTR_READ_ONLY or ATTR_ARCHIVE, attr, "only R|A user bits must be set")
        assertEquals(0, attr and ATTR_HIDDEN, "H must be clear")
        assertEquals(0, attr and ATTR_SYSTEM, "S must be clear")
    }

    // ----- case 2: VOLUME_ID and DIRECTORY bits are preserved ----------------

    @Test
    fun preservesVolIdAndDirectoryBits() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        // Setting user bits on the folder must NOT clear its DIRECTORY (0x10) bit.
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/SUBDIR", ATTR_HIDDEN),
        )
        val dirAttr = rootAttrByte(device, "SUBDIR")
        assertTrue((dirAttr and ATTR_DIRECTORY) != 0, "DIRECTORY bit must survive")
        assertTrue((dirAttr and ATTR_HIDDEN) != 0, "the new H user bit must be set")

        // A user can never SET the VOLUME_ID (0x08) bit via attrs — it is masked off.
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/DATA.BIN", ATTR_VOLUME_ID or ATTR_READ_ONLY),
        )
        val fileAttr = rootAttrByte(device, "DATA.BIN")
        assertEquals(0, fileAttr and ATTR_VOLUME_ID, "VOLUME_ID must never be user-settable")
        assertTrue((fileAttr and ATTR_READ_ONLY) != 0, "the R user bit must still apply")
    }

    // ----- case 3: a folder still lists as a directory after the edit --------

    @Test
    fun folder_preservesDirectoryBit() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        val before = (vol.list("") as Fat12Result.Ok).value
        val countBefore = before.size

        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/SUBDIR", ATTR_READ_ONLY or ATTR_SYSTEM),
        )

        val after = (vol.list("") as Fat12Result.Ok).value
        assertEquals(countBefore, after.size, "list count must be unchanged")
        val sub = after.firstOrNull { it.name.equals("SUBDIR", ignoreCase = true) }
        assertNotNull(sub, "SUBDIR must still be present")
        assertTrue(sub!!.isDirectory, "SUBDIR must still be a directory")
    }

    // ----- case 4: INT-02 byte-for-byte rollback on a mid-write fault ---------

    @Test
    fun midWriteFailure_rollsBackByteForByte() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        val snapshot = device.bytes.copyOf()

        // The single write target is the PARENT dir region (root dir here).
        device.failNextWriteAt(bpb.rootDirOffset)

        assertThrows<FatWriteFailedException> {
            vol.setAttributes("/DATA.BIN", ATTR_READ_ONLY or ATTR_HIDDEN)
        }

        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: setAttributes must roll back byte-for-byte on a mid-write failure",
        )
    }

    // ----- case 5: attributes persist across a close / re-open (SC#1) --------

    @Test
    fun attributes_persistAcrossReopen() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/DATA.BIN", ATTR_READ_ONLY or ATTR_SYSTEM),
        )

        // Re-open a fresh volume over the SAME device (simulates a re-mount).
        val reopened = openVolume(device)
        val entries = (reopened.list("") as Fat12Result.Ok).value
        val data = entries.first { it.name.equals("DATA.BIN", ignoreCase = true) }
        assertTrue((data.attributes and ATTR_READ_ONLY) != 0, "R must persist across reopen")
        assertTrue((data.attributes and ATTR_SYSTEM) != 0, "S must persist across reopen")
        assertEquals(0, data.attributes and ATTR_ARCHIVE, "A must have been cleared")
    }

    // ----- case 6: missing entry / missing parent -> NotFound, no bytes ------

    @Test
    fun invalidPath_NotFound() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        val snapshot = device.bytes.copyOf()

        // Missing leaf in an existing (root) parent.
        assertInstanceOf(
            Fat12Result.NotFound::class.java,
            vol.setAttributes("/NOPE.BIN", ATTR_READ_ONLY),
        )
        // Missing parent directory.
        assertInstanceOf(
            Fat12Result.NotFound::class.java,
            vol.setAttributes("/GHOSTDIR/FILE.BIN", ATTR_READ_ONLY),
        )

        assertArrayEquals(
            snapshot,
            device.bytes,
            "a NotFound path must change no bytes",
        )
    }

    // ----- case 7: re-applying the current attrs is a byte-identical no-op ----

    @Test
    fun noOp_idempotent() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        // DATA.BIN's current attr is ARCHIVE (0x20). Re-apply exactly that.
        val snapshot = device.bytes.copyOf()
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.setAttributes("/DATA.BIN", ATTR_ARCHIVE),
        )
        assertArrayEquals(
            snapshot,
            device.bytes,
            "re-applying the current attrs must leave every byte identical",
        )
    }
}
