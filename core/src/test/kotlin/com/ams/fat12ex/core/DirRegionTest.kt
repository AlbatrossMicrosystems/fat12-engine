package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DirRegion tests covering the directory-breadth invariants:
 *   - walk groups LFN preamble entries with their trailing short entry
 *     (incl. on a concatenated subdirectory cluster chain)
 *   - markDeletedAndFreeChain marks every LFN + short slot 0xE5 and frees the chain
 *   - LFN reverse-ordinal + 0x40 flag + identical checksum
 *   - `.`/`..` cluster values, with `..`-of-root = 0x0000
 *   - 0xE5 / 0x05 first-byte substitution round-trip
 *   - allocateEntry finds a consecutive free run
 *
 * JUnit 5; engine is synchronous.
 */
class DirRegionTest {

    /** Copy the 2048-byte (4-sector) root-dir region out of a built default volume. */
    private fun rootBytes(builder: Fat12ImageBuilder): ByteArray {
        val device = builder.build()
        // Default builder: rsvdSecCnt=1, numFATs=2, fatSz=3 -> root dir starts at sector 7,
        // size = ceil(64*32/512) = 4 sectors = 2048 bytes.
        val rootStart = (1 + 2 * 3) * 512
        val rootBytes = ByteArray(2048)
        device.bytes.copyInto(rootBytes, 0, rootStart, rootStart + 2048)
        return rootBytes
    }

    /** Copy FAT[0] (fatSz=3 sectors) out of a built default volume. */
    private fun fatBytes(builder: Fat12ImageBuilder): ByteArray {
        val device = builder.build()
        val fat = ByteArray(3 * 512)
        device.bytes.copyInto(fat, 0, 1 * 512, 1 * 512 + fat.size)
        return fat
    }

    // ---- Ported scaffold (from RootDirTest, adapted to DirRegion) ----

    @Test
    fun walk_groupsLfnPreambleWithShortEntry() {
        val builder = Fat12ImageBuilder()
            .withFirmwareLfn(
                displayName = "sample_long_lfn_filename_for_testing_v2.bin",   // 43 chars -> 4 LFN entries
                clusters = (5..30).toList(),
                bytes = ByteArray(307839),
            )
        val root = rootBytes(builder)
        val entries = DirRegion.walk(root)
        assertEquals(1, entries.size, "Builder emits 1 LFN file -> walk returns 1 entry")
        val e = entries.single()
        assertEquals(4, e.lfnEntryCount, "LFN preamble must be exactly 4 entries")
        assertEquals(5, e.firstCluster, "first cluster")
        assertEquals(307839, e.fileSize, "file size")
    }

    @Test
    fun delete_marksAllEntriesDeletedAndFreesChain() {
        val builder = Fat12ImageBuilder()
            .withFirmwareLfn(
                displayName = "sample_long_lfn_filename_for_testing_v2.bin",   // 43 chars -> 4 LFN entries
                clusters = (5..30).toList(),
                bytes = ByteArray(307839),
            )
        val root = rootBytes(builder)
        val fat = fatBytes(builder)

        val entry = DirRegion.walk(root).single()
        val freed = DirRegion.markDeletedAndFreeChain(root, fat, entry)

        assertTrue(freed >= 1, "freed at least one cluster")
        val preambleStart = entry.shortEntryOffset - 4 * DirRegion.ENTRY_SIZE
        for (i in 0 until 5) {
            val off = preambleStart + i * DirRegion.ENTRY_SIZE
            assertEquals(
                0xE5.toByte(),
                root[off],
                "Entry at offset $off must be marked 0xE5 after delete",
            )
        }
        assertEquals(
            FatTable.FREE,
            FatTable.read12(fat, 5),
            "FAT entry for first cluster must be FREE after delete",
        )
    }

    // ---- LFN reverse-ordinal + 0x40 + checksum ----

    @Test
    fun lfn_firstPhysicalEntry_hasHighestOrdinalWith0x40() {
        // 20-char name -> ceil(20/13) = 2 LFN entries.
        val short83 = "LONGNA~1TXT"
        val entries = DirRegion.encodeLfnPreamble("a_twenty_char_name__", short83)
        assertEquals(2, entries.size, "20-char name needs 2 LFN entries")
        val nEntries = entries.size
        assertEquals(
            (nEntries or 0x40).toByte(),
            entries.first()[0],
            "first physical slot carries highest ordinal with 0x40 set",
        )
        assertEquals(
            1.toByte(),
            entries.last()[0],
            "last physical slot is ordinal 1",
        )
    }

    @Test
    fun lfn_allEntries_shareIdenticalChecksum() {
        val short83 = "LONGNA~1TXT"
        val expected = DirRegion.lfnChecksum(short83)
        val entries = DirRegion.encodeLfnPreamble("a_twenty_char_name__", short83)
        for ((i, e) in entries.withIndex()) {
            assertEquals(
                expected,
                e[0x0D],
                "LFN entry $i checksum (0x0D) must equal lfnChecksum(short83)",
            )
            assertEquals(0x0F.toByte(), e[0x0B], "LFN entry $i attr (0x0B) must be ATTR_LFN")
        }
    }

    // ---- `.` / `..` cluster values ----

    @Test
    fun dotDot_rootParent_isZero() {
        val buf = ByteArray(DirRegion.ENTRY_SIZE * 4)
        DirRegion.writeDotDotEntries(buf, ownCluster = 5, parentCluster = DirRegion.ROOT_DIR_MARKER)
        val entries = DirRegion.walk(buf)
        assertEquals(2, entries.size, "buffer holds exactly . and .. entries")
        val dot = entries[0]
        val dotDot = entries[1]
        assertEquals(".", dot.shortName83.trim(), ". entry name")
        assertEquals(5, dot.firstCluster, ". entry first cluster = own cluster")
        assertEquals("..", dotDot.shortName83.trim(), ".. entry name")
        assertEquals(0x0000, dotDot.firstCluster, ".. of root must be 0x0000")
    }

    @Test
    fun dotDot_nestedSubdir_isParentCluster() {
        val buf = ByteArray(DirRegion.ENTRY_SIZE * 4)
        DirRegion.writeDotDotEntries(buf, ownCluster = 9, parentCluster = 4)
        val entries = DirRegion.walk(buf)
        val dotDot = entries.single { it.shortName83.trim() == ".." }
        val dot = entries.single { it.shortName83.trim() == "." }
        assertEquals(9, dot.firstCluster, ". = own cluster")
        assertEquals(4, dotDot.firstCluster, ".. = parent cluster")
    }

    // ---- 0xE5 / 0x05 substitution ----

    @Test
    fun shortName_0xE5_writtenAs0x05OnDisk() {
        val buf = ByteArray(DirRegion.ENTRY_SIZE * 2)
        // Build an 11-byte name whose first byte is 0xE5.
        val name83 = String(charArrayOf(0xE5.toChar())) + "ANJI   TXT".take(10)
        assertEquals(11, name83.length, "name83 must be 11 chars")
        DirRegion.writeShortEntryGeneric(
            dirBytes = buf,
            offset = 0,
            name83 = name83,
            attr = DirRegion.ATTR_ARCHIVE,
            caseByte = 0,
            firstCluster = 7,
            fileSize = 100,
            dosDate = DirRegion.DEFAULT_DOS_DATE,
            dosTime = DirRegion.DEFAULT_DOS_TIME,
        )
        assertEquals(0x05.toByte(), buf[0], "leading 0xE5 must be stored as 0x05 on disk")
    }

    @Test
    fun walk_reads0x05AsValidEntry_decodesTo0xE5() {
        val buf = ByteArray(DirRegion.ENTRY_SIZE * 2)
        val name83 = String(charArrayOf(0xE5.toChar())) + "ANJI   TXT".take(10)
        DirRegion.writeShortEntryGeneric(
            dirBytes = buf,
            offset = 0,
            name83 = name83,
            attr = DirRegion.ATTR_ARCHIVE,
            caseByte = 0,
            firstCluster = 7,
            fileSize = 100,
            dosDate = DirRegion.DEFAULT_DOS_DATE,
            dosTime = DirRegion.DEFAULT_DOS_TIME,
        )
        // Raw on-disk first byte is 0x05 (not the end-of-dir sentinel), so walk includes it.
        val entries = DirRegion.walk(buf)
        assertEquals(1, entries.size, "leading-0x05 entry must be returned by walk")
        assertEquals(
            0xE5.toByte(),
            entries.single().shortName83[0].code.toByte(),
            "walk must decode the leading 0x05 back to 0xE5",
        )
    }

    // ---- Subdirectory walk (generalization beyond the fixed root region) ----

    @Test
    fun subdirectory_walkReturnsAllEntries() {
        // Concatenated 2-cluster subdir buffer (1024 bytes = 32 entries).
        val buf = ByteArray(1024)
        // . and .. at the start, then 3 plain short entries.
        DirRegion.writeDotDotEntries(buf, ownCluster = 9, parentCluster = 4)
        val names = listOf("FILEA   TXT", "FILEB   TXT", "FILEC   TXT")
        names.forEachIndexed { idx, n ->
            DirRegion.writeShortEntryGeneric(
                dirBytes = buf,
                offset = (2 + idx) * DirRegion.ENTRY_SIZE,
                name83 = n,
                attr = DirRegion.ATTR_ARCHIVE,
                caseByte = 0,
                firstCluster = 10 + idx,
                fileSize = 50,
                dosDate = DirRegion.DEFAULT_DOS_DATE,
                dosTime = DirRegion.DEFAULT_DOS_TIME,
            )
        }
        val entries = DirRegion.walk(buf)
        assertEquals(5, entries.size, "walk must return . + .. + 3 files (proves subdir generalization)")
        assertTrue(entries.any { it.shortName83 == "FILEA   TXT" })
        assertTrue(entries.any { it.shortName83 == "FILEC   TXT" })
    }

    @Test
    fun findEntry_locatesByShortName() {
        val buf = ByteArray(1024)
        DirRegion.writeShortEntryGeneric(
            dirBytes = buf,
            offset = 0,
            name83 = "FILEA   TXT",
            attr = DirRegion.ATTR_ARCHIVE,
            caseByte = 0,
            firstCluster = 10,
            fileSize = 50,
            dosDate = DirRegion.DEFAULT_DOS_DATE,
            dosTime = DirRegion.DEFAULT_DOS_TIME,
        )
        val found = DirRegion.findEntry(buf, "FILEA   TXT")
        assertNotNull(found, "findEntry must locate an existing short name")
        assertEquals(10, found!!.firstCluster)
    }

    // ---- allocateEntry ----

    @Test
    fun allocateEntry_findsConsecutiveFreeRun() {
        val buf = ByteArray(DirRegion.ENTRY_SIZE * 8)
        // Occupy slots 0 and 1 with real entries; leave slots 2..7 free (0x00).
        for (s in 0..1) {
            DirRegion.writeShortEntryGeneric(
                dirBytes = buf,
                offset = s * DirRegion.ENTRY_SIZE,
                name83 = "OCC$s    TXT".take(11).padEnd(11, ' '),
                attr = DirRegion.ATTR_ARCHIVE,
                caseByte = 0,
                firstCluster = 2 + s,
                fileSize = 1,
                dosDate = DirRegion.DEFAULT_DOS_DATE,
                dosTime = DirRegion.DEFAULT_DOS_TIME,
            )
        }
        val off = DirRegion.allocateEntry(buf, 3)
        assertEquals(2 * DirRegion.ENTRY_SIZE, off, "first run of 3 free slots starts at slot 2")
    }

    @Test
    fun allocateEntry_returnsMinusOneWhenNoRunFits() {
        // 3 slots all occupied -> no room for a run of 2.
        val buf = ByteArray(DirRegion.ENTRY_SIZE * 3)
        for (s in 0..2) {
            DirRegion.writeShortEntryGeneric(
                dirBytes = buf,
                offset = s * DirRegion.ENTRY_SIZE,
                name83 = "OCC$s    TXT".take(11).padEnd(11, ' '),
                attr = DirRegion.ATTR_ARCHIVE,
                caseByte = 0,
                firstCluster = 2 + s,
                fileSize = 1,
                dosDate = DirRegion.DEFAULT_DOS_DATE,
                dosTime = DirRegion.DEFAULT_DOS_TIME,
            )
        }
        assertEquals(-1, DirRegion.allocateEntry(buf, 2), "no consecutive free run -> -1 (DiskFull signal)")
    }
}
