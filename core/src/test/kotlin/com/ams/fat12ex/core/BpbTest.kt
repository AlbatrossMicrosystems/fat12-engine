package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Tests Bpb.parse against synthetic FAT12 images.
 *
 * Mandatory coverage:
 *   - Valid FAT12 BPB -> parsed fields + derived offsets correct
 *   - >= 4085-cluster BPB -> NotFat12Exception (FAT16/FAT32 rejection contract)
 *   - "FAT     " label still accepted (cluster count is binding, not the label)
 *   - cluster-count boundary at exactly 4085
 *
 * Migrated from the source engine JUnit 4 suite to JUnit 5 Jupiter:
 *   - org.junit.Assert.* -> org.junit.jupiter.api.Assertions.*
 *   - org.junit.Test     -> org.junit.jupiter.api.Test
 *   - @Test(expected = X::class) -> assertThrows(X::class.java) { ... }
 *   - assertEquals/assertTrue message argument moved from FIRST to LAST.
 *   - parse_tooManyClusters now asserts the cluster-count number appears in the
 *     hint (the app-specific bl-mode hint string was stripped during the port).
 */
class BpbTest {

    @Test
    fun parse_validFat12_returnsExpectedFields() {
        val device = Fat12ImageBuilder().build()
        val bpb = Bpb.parse(device)

        assertEquals(512, bpb.bytsPerSec, "bytsPerSec")
        assertEquals(8, bpb.secPerClus, "secPerClus")
        assertEquals(1, bpb.rsvdSecCnt, "rsvdSecCnt")
        assertEquals(2, bpb.numFATs, "numFATs")
        assertEquals(64, bpb.rootEntCnt, "rootEntCnt")
        assertEquals(3, bpb.fatSz, "fatSz")
        assertEquals(4096, bpb.clusterSize, "clusterSize (= bytsPerSec * secPerClus)")
        // FAT[0] starts after 1 reserved sector -> byte offset 512.
        assertEquals(512L, bpb.fat0Offset, "fat0Offset")
        // FAT[1] starts after FAT[0] (3 sectors) -> byte offset 512 + 3*512 = 2048.
        assertEquals(2048L, bpb.fat1Offset, "fat1Offset")
        // Root dir starts after both FATs (1 + 2*3 = 7 sectors) -> byte offset 7 * 512 = 3584.
        assertEquals(3584L, bpb.rootDirOffset, "rootDirOffset")
        // Root dir: 64 entries x 32 bytes = 2048 bytes = 4 sectors.
        assertEquals(4, bpb.rootDirSectors, "rootDirSectors")
        // Data area starts after root dir -> byte offset 3584 + 4*512 = 5632.
        assertEquals(5632L, bpb.dataAreaOffset, "dataAreaOffset")
    }

    @Test
    fun parse_tooManyClusters_throwsNotFat12WithClusterCount() {
        // FAT type is determined by cluster count, not the BS_FilSysType label.
        // A volume with >= 4085 clusters is NOT FAT12 (e.g. a larger LUN). The
        // app-specific bl-mode hint is gone, so the exception is checked for the
        // actual cluster count instead. 4090 clusters at 1 sector/cluster.
        val device = Fat12ImageBuilder(secPerClus = 1, fatSz = 12, clusterCount = 4090).build()
        try {
            Bpb.parse(device)
            fail("Expected NotFat12Exception")
        } catch (e: NotFat12Exception) {
            assertNotNull(e.hint, "hint must be populated")
            assertTrue(
                e.hint.contains("4090"),
                "hint must report the actual cluster count"
            )
            assertTrue(
                e.message!!.contains("4090"),
                "exception message must report the actual cluster count"
            )
        }
    }

    @Test
    fun parse_clusterCountAt4085_isRejected() {
        // Boundary: the gate is `clusterCount >= FAT12_MAX_CLUSTERS` (4085),
        // so a volume with EXACTLY 4085 data clusters is NOT FAT12 and must be
        // rejected (4084 is the last valid FAT12 cluster count).
        val device = Fat12ImageBuilder(secPerClus = 1, fatSz = 12, clusterCount = 4085).build()
        val e = assertThrows(NotFat12Exception::class.java) {
            Bpb.parse(device)
        }
        assertTrue(
            e.message!!.contains("4085"),
            "exception must report the boundary cluster count 4085"
        )
    }

    @Test
    fun parse_clusterCountAt4084_isAccepted() {
        // The complementary side of the boundary: 4084 clusters (one below
        // FAT12_MAX_CLUSTERS) IS a valid FAT12 volume and must be accepted.
        val device = Fat12ImageBuilder(secPerClus = 1, fatSz = 12, clusterCount = 4084).build()
        val bpb = Bpb.parse(device)
        assertEquals(4084, bpb.clusterCount, "clusterCount")
        assertTrue(bpb.clusterCount < Bpb.FAT12_MAX_CLUSTERS, "4084 < 4085 -> FAT12")
    }

    @Test
    fun parse_fat12VolumeWithFatLabel_accepted() {
        // Regression: real FAT12 media labels its volume "FAT     ", not
        // "FAT12   ". BS_FilSysType is informational only; cluster count is
        // binding, so this volume MUST be accepted.
        val device = Fat12ImageBuilder(fsType = "FAT     ").build()
        val bpb = Bpb.parse(device)
        assertTrue(bpb.clusterCount < Bpb.FAT12_MAX_CLUSTERS, "954-cluster volume must be detected as FAT12")
        assertEquals(954, bpb.clusterCount, "clusterCount")
    }

    @Test
    fun byteOffsetOfCluster_returnsDataAreaForCluster2() {
        val bpb = Bpb.parse(Fat12ImageBuilder().build())
        assertEquals(bpb.dataAreaOffset, bpb.byteOffsetOfCluster(2))
        assertEquals(bpb.dataAreaOffset + 4096L, bpb.byteOffsetOfCluster(3))
    }

    @Test
    fun byteOffsetOfCluster_rejectsBelowTwo() {
        val bpb = Bpb.parse(Fat12ImageBuilder().build())
        assertThrows(IllegalArgumentException::class.java) {
            bpb.byteOffsetOfCluster(1)
        }
    }
}
