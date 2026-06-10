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
 * [Fat12Volume] structural-op tests — mkdir / rename / recursive-delete.
 *
 * Proves the correctness invariants for the compound write ops:
 *   - a new directory's `.` entry → its own cluster; `..` → the parent's
 *     cluster, or 0x0000 when the parent is the root directory.
 *   - rename to a long name writes a reverse-ordinal LFN preamble (decoded
 *     name visible in list()) while preserving the entry's first cluster + file size.
 *   - recursive delete is post-order — every freed chain is freed exactly once,
 *     so countFreeClusters balances (no double-free, no leaked clusters).
 *
 * And extends the atomic rollback contract to COMPOUND ops:
 * for each of mkdir / rename / recursive-delete an injected mid-op write
 * failure leaves `device.bytes` byte-for-byte identical to its pre-op snapshot.
 */
class Fat12VolumeOpsTest {

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    /** Read the raw directory region for a subdirectory's first cluster (own region). */
    private fun readDirCluster(device: InMemoryBlockDevice, bpb: Bpb, cluster: Int): ByteArray {
        val chain = FatTable.walkChain(readBytes(device, bpb.fat0Offset, bpb.fatBytes), cluster)
        val buf = ByteArray(chain.size * bpb.clusterSize)
        for ((i, c) in chain.withIndex()) {
            readBytes(device, bpb.byteOffsetOfCluster(c), bpb.clusterSize).copyInto(buf, i * bpb.clusterSize)
        }
        return buf
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    // ----- mkdir: '.'/'..' correctness ---------------------------------------

    @Test
    fun mkdir_inRoot_dotDotIsZero() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/sub"))

        // The new directory entry is in the root, attr = directory.
        val rootEntries = (vol.list("/") as Fat12Result.Ok).value
        val sub = rootEntries.firstOrNull { it.shortName.trim() == "SUB" }
        assertNotNull(sub, "mkdir must add SUB to the root directory")
        assertTrue(sub!!.isDirectory, "SUB must be a directory")
        val subCluster = sub.firstCluster

        // Read SUB's own cluster: '.' = SUB's cluster, '..' = 0x0000 (parent is root).
        val dir = readDirCluster(device, bpb, subCluster)
        // '.' at offset 0, '..' at offset 32.
        assertEquals(subCluster, u16(dir, 0x00 + 0x1A), "'.' firstCluster must equal the new dir's own cluster")
        assertEquals(0x0000, u16(dir, 0x20 + 0x1A), "'..' firstCluster must be 0x0000 when parent is root")
    }

    @Test
    fun mkdir_nested_dotDotIsParentCluster() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/a"))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/a/b"))

        val aCluster = (vol.list("/") as Fat12Result.Ok).value.first { it.shortName.trim() == "A" }.firstCluster
        val bCluster = (vol.list("/a") as Fat12Result.Ok).value.first { it.shortName.trim() == "B" }.firstCluster

        val bDir = readDirCluster(device, bpb, bCluster)
        assertEquals(bCluster, u16(bDir, 0x00 + 0x1A), "'.' of b must equal b's own cluster")
        assertEquals(aCluster, u16(bDir, 0x20 + 0x1A), "'..' of b must equal a's cluster (not 0x0000)")
    }

    @Test
    fun mkdir_nameConflict_returnsNameConflict() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/dup"))
        val result = vol.mkdir("/dup")
        assertInstanceOf(Fat12Result.NameConflict::class.java, result)
    }

    // ----- rename: LFN + metadata preserved ----------------------------------

    @Test
    fun rename_longName_writesLfnAndPreservesMetadata() {
        val content = ByteArray(3000) { (it and 0x7F).toByte() }
        val device = Fat12ImageBuilder()
            .withReservedShortEntry("OLD     TXT", attr = 0x20, clusters = listOf(2), bytes = content)
            .build()
        val vol = openVolume(device)

        val before = (vol.list("/") as Fat12Result.Ok).value.first { it.shortName == "OLD     TXT" }
        val beforeCluster = before.firstCluster
        val beforeSize = before.size

        val newName = "longfilename.txt"   // exceeds 8.3 -> LFN preamble required
        assertInstanceOf(Fat12Result.Ok::class.java, vol.rename("/OLD.TXT", newName))

        val entries = (vol.list("/") as Fat12Result.Ok).value
        // The old name is gone; the new LFN display name is present.
        assertTrue(entries.none { it.shortName == "OLD     TXT" }, "old entry slots must be deleted")
        val renamed = entries.firstOrNull { it.name.equals(newName, ignoreCase = true) }
        assertNotNull(renamed, "renamed entry must list with its LFN display name")
        // first cluster + file size preserved (only the name changed).
        assertEquals(beforeCluster, renamed!!.firstCluster, "rename must preserve firstCluster")
        assertEquals(beforeSize, renamed.size, "rename must preserve fileSize")

        // The file still reads back identically under its new name (chain untouched).
        val readBack = vol.readFile("/$newName")
        assertInstanceOf(Fat12Result.Ok::class.java, readBack)
        assertArrayEquals(content, (readBack as Fat12Result.Ok).value, "renamed file must read back identical")
    }

    @Test
    fun rename_toExistingSibling_returnsNameConflict() {
        val device = Fat12ImageBuilder()
            .withReservedShortEntry("A       TXT", attr = 0x20, clusters = listOf(2), bytes = ByteArray(10))
            .withReservedShortEntry("B       TXT", attr = 0x20, clusters = listOf(3), bytes = ByteArray(10))
            .build()
        val vol = openVolume(device)

        val result = vol.rename("/A.TXT", "B.TXT")
        assertInstanceOf(Fat12Result.NameConflict::class.java, result)
    }

    // ----- delete: free accounting + post-order ------------------------------

    @Test
    fun delete_file_freesClusters() {
        val content = ByteArray(3000) { (it and 0x7F).toByte() }   // 1 cluster (4 KB)
        val device = Fat12ImageBuilder()
            .withReservedShortEntry("DATA    BIN", attr = 0x20, clusters = listOf(2), bytes = content)
            .build()
        val vol = openVolume(device)

        val freeBefore = vol.freeBytes()
        assertInstanceOf(Fat12Result.Ok::class.java, vol.delete("/DATA.BIN"))
        val freeAfter = vol.freeBytes()

        assertEquals(
            freeBefore + vol.clusterSize(),
            freeAfter,
            "deleting a 1-cluster file must free exactly one cluster's worth of space",
        )
        // Entry gone from the listing.
        assertTrue(
            (vol.list("/") as Fat12Result.Ok).value.none { it.shortName == "DATA    BIN" },
            "deleted file must not be listed",
        )
    }

    @Test
    fun delete_recursive_postOrder_noLeakedClusters() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)
        val cs = vol.clusterSize()

        // Build a 3-level tree: /A (dir) -> /A/B (dir) + /A/f1.txt; /A/B/f2.txt.
        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/A"))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/A/B"))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.writeFile("/A/f1.txt", sequenceOf(ByteArray(cs + 10) { 1 })))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.writeFile("/A/B/f2.txt", sequenceOf(ByteArray(cs) { 2 })))

        fun fat() = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        // Free count with the tree present (the /A tree is the only thing on this volume).
        val freeWithTree = FatTable.countFreeClusters(fat(), bpb.clusterCount)
        // On this freshly-formatted volume nothing else is allocated, so every occupied
        // cluster belongs to the /A tree.
        val occupiedByTree = bpb.clusterCount - freeWithTree

        // Capture the exact set of clusters the tree owns, to assert NONE is freed twice
        // and ALL are reclaimed.
        val treeClusters = collectTreeClusters(device, bpb, vol)
        assertEquals(occupiedByTree, treeClusters.size, "tree cluster set must equal the occupied count (no other allocations)")

        assertInstanceOf(Fat12Result.Ok::class.java, vol.delete("/A", recursive = true))

        val freeAfter = FatTable.countFreeClusters(fat(), bpb.clusterCount)

        // free-after == free-with-tree + every cluster the tree owned (no leak),
        // and the directory is gone.
        assertEquals(
            freeWithTree + treeClusters.size,
            freeAfter,
            "post-order recursive delete must reclaim every tree cluster exactly once (no leak, no double-free)",
        )
        // Every previously-occupied cluster is now FREE (proves none leaked).
        val fatAfter = fat()
        for (c in treeClusters) {
            assertEquals(FatTable.FREE, FatTable.read12(fatAfter, c), "cluster $c must be FREE after recursive delete")
        }
        assertTrue(
            (vol.list("/") as Fat12Result.Ok).value.none { it.shortName.trim() == "A" },
            "deleted directory must not be listed",
        )
        // Both FAT copies stay byte-identical.
        val fat0 = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        val fat1 = readBytes(device, bpb.fat1Offset, bpb.fatBytes)
        assertArrayEquals(fat0, fat1, "FAT[0] and FAT[1] must remain byte-identical after recursive delete")
    }

    /** Collect EVERY data cluster owned by the /A tree (dirs + files), used to prove exact reclaim. */
    private fun collectTreeClusters(device: InMemoryBlockDevice, bpb: Bpb, vol: Fat12Volume): Set<Int> {
        val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        val clusters = linkedSetOf<Int>()
        fun walkDir(dirCluster: Int) {
            clusters.addAll(FatTable.walkChain(fat, dirCluster))
            val buf = readDirCluster(device, bpb, dirCluster)
            for (e in DirRegion.walk(buf)) {
                val n = e.shortName83.trim()
                if (n == "." || n == "..") continue
                if (e.firstCluster < 2) continue
                if ((e.attr and DirRegion.ATTR_DIRECTORY) != 0) {
                    walkDir(e.firstCluster)
                } else {
                    clusters.addAll(FatTable.walkChain(fat, e.firstCluster))
                }
            }
        }
        val aCluster = (vol.list("/") as Fat12Result.Ok).value.first { it.shortName.trim() == "A" }.firstCluster
        walkDir(aCluster)
        return clusters
    }

    // ----- per-op rollback proofs (INT-02 / Success Criterion #5) ------------

    @Test
    fun mkdir_midOpFailure_rollsBack() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        val snapshot = device.bytes.copyOf()

        // Fail the FINAL write (the parent/root directory entry): by then the new dir
        // cluster + FAT[0] + FAT[1] are already written, so rollback must zero the new
        // cluster AND replay the FAT + parent-dir pre-images. The most demanding case.
        device.failNextWriteAt(bpb.rootDirOffset)

        assertThrows<FatWriteFailedException> { vol.mkdir("/sub") }

        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: mkdir must roll the volume back byte-for-byte to pre-op state on a mid-op failure",
        )
        assertTrue(
            (vol.list("/") as Fat12Result.Ok).value.none { it.shortName.trim() == "SUB" },
            "no directory entry may be committed after mkdir rollback",
        )
    }

    @Test
    fun rename_midOpFailure_rollsBack() {
        val device = Fat12ImageBuilder()
            .withReservedShortEntry("OLD     TXT", attr = 0x20, clusters = listOf(2), bytes = ByteArray(100))
            .build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)

        val snapshot = device.bytes.copyOf()

        // rename has a single write (the parent directory region). Fail it; the region
        // was captured before the write, so rollback restores it exactly.
        device.failNextWriteAt(bpb.rootDirOffset)

        assertThrows<FatWriteFailedException> { vol.rename("/OLD.TXT", "renamed_long_name.txt") }

        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: rename must roll the volume back byte-for-byte to pre-op state on a mid-op failure",
        )
        // The original entry survives unchanged.
        assertTrue(
            (vol.list("/") as Fat12Result.Ok).value.any { it.shortName == "OLD     TXT" },
            "the original entry must be intact after rename rollback",
        )
    }

    @Test
    fun deleteRecursive_midOpFailure_rollsBack() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)
        val cs = vol.clusterSize()

        // Build a small tree first (committed cleanly).
        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/T"))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/T/U"))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.writeFile("/T/U/leaf.bin", sequenceOf(ByteArray(cs) { 7 })))

        // Snapshot AFTER the tree is built — the recursive delete must restore exactly this.
        val snapshot = device.bytes.copyOf()

        // Fail the FAT[1] write mid-delete: the whole subtree FAT has been mutated in
        // memory and FAT[0] written; FAT[1] write throws. Rollback replays FAT[0],
        // the captured subtree dir clusters, and (the not-yet-written) parent dir.
        device.failNextWriteAt(bpb.fat1Offset)

        assertThrows<FatWriteFailedException> { vol.delete("/T", recursive = true) }

        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02: recursive delete must roll the volume back byte-for-byte to pre-op state on a mid-op failure",
        )
        // The tree is intact: /T still lists, and the leaf still reads back.
        assertTrue(
            (vol.list("/") as Fat12Result.Ok).value.any { it.shortName.trim() == "T" },
            "the directory tree must be intact after recursive-delete rollback",
        )
        val leaf = vol.readFile("/T/U/leaf.bin")
        assertInstanceOf(Fat12Result.Ok::class.java, leaf)
    }
}
