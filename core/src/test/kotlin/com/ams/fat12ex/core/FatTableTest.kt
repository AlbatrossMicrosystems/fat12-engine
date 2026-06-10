package com.ams.fat12ex.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Exhaustive FAT12 12-bit pack/unpack tests.
 *
 * The 12-bit packed format is the highest-risk surface in the phase — a
 * single off-by-one nibble corrupts the entire FAT chain. Tests cover:
 *   - Even-N reads
 *   - Odd-N reads
 *   - EOC / FREE / BAD round-trip
 *   - Neighbor-nibble preservation on write
 *   - Chain walk + link + free
 *   - Allocator: free space exhausted, contiguous, scattered allocation
 *
 * Migrated from the source engine JUnit 4 suite to JUnit 5 Jupiter:
 *   - org.junit.Assert.* -> org.junit.jupiter.api.Assertions.*
 *   - org.junit.Test     -> org.junit.jupiter.api.Test
 *   - assertEquals/assertTrue message argument moved from FIRST (JUnit 4) to
 *     LAST (JUnit 5).
 */
class FatTableTest {

    private fun freshFat(bytes: Int = 1536): ByteArray {
        val fat = ByteArray(bytes)
        FatTable.write12(fat, 0, 0xFF8)
        FatTable.write12(fat, 1, 0xFFF)
        return fat
    }

    // === pack/unpack edge cases ===

    @Test
    fun pack_unpack_evenCluster() {
        val fat = freshFat()
        FatTable.write12(fat, 2, 0x123)
        FatTable.write12(fat, 4, 0xABC)
        FatTable.write12(fat, 6, 0x000)
        assertEquals(0x123, FatTable.read12(fat, 2))
        assertEquals(0xABC, FatTable.read12(fat, 4))
        assertEquals(0x000, FatTable.read12(fat, 6))
    }

    @Test
    fun pack_unpack_oddCluster() {
        val fat = freshFat()
        FatTable.write12(fat, 3, 0x456)
        FatTable.write12(fat, 5, 0xDEF)
        FatTable.write12(fat, 7, 0xFFF)
        assertEquals(0x456, FatTable.read12(fat, 3))
        assertEquals(0xDEF, FatTable.read12(fat, 5))
        assertEquals(0xFFF, FatTable.read12(fat, 7))
    }

    @Test
    fun pack_unpack_eocAndFreeAndBad() {
        val fat = freshFat()
        for (v in listOf(0xFF8, 0xFF9, 0xFFA, 0xFFB, 0xFFC, 0xFFD, 0xFFE, 0xFFF)) {
            FatTable.write12(fat, 2, v)
            assertEquals(v, FatTable.read12(fat, 2), "EOC round-trip for 0x${v.toString(16).uppercase()}")
            assertTrue(FatTable.isEoc(v), "isEoc must accept 0x${v.toString(16).uppercase()}")
        }
        FatTable.write12(fat, 2, FatTable.FREE)
        assertEquals(FatTable.FREE, FatTable.read12(fat, 2))
        assertTrue(!FatTable.isEoc(FatTable.FREE), "FREE must NOT be EOC")
        FatTable.write12(fat, 2, FatTable.BAD)
        assertEquals(FatTable.BAD, FatTable.read12(fat, 2))
        assertTrue(!FatTable.isEoc(FatTable.BAD), "BAD must NOT be EOC")
    }

    @Test
    fun pack_preservesNeighborNibble_evenThenOdd() {
        val fat = freshFat()
        FatTable.write12(fat, 3, 0xABC)
        FatTable.write12(fat, 2, 0x123)
        assertEquals(0x123, FatTable.read12(fat, 2), "entry 2 round-trip")
        assertEquals(0xABC, FatTable.read12(fat, 3), "entry 3 must survive write of 2")
    }

    @Test
    fun pack_preservesNeighborNibble_oddThenEven() {
        val fat = freshFat()
        FatTable.write12(fat, 2, 0x123)
        FatTable.write12(fat, 3, 0xABC)
        assertEquals(0xABC, FatTable.read12(fat, 3), "entry 3 round-trip")
        assertEquals(0x123, FatTable.read12(fat, 2), "entry 2 must survive write of 3")
    }

    @Test
    fun pack_allValues_roundTripBitExact() {
        for (v in 0..0xFFF) {
            val fat = freshFat()
            FatTable.write12(fat, 2, v)
            FatTable.write12(fat, 3, v xor 0xFFF)
            assertEquals(v, FatTable.read12(fat, 2), "even N=2 v=$v")
            assertEquals(v xor 0xFFF, FatTable.read12(fat, 3), "odd N=3 v=${v xor 0xFFF}")
        }
    }

    // === chain operations ===

    @Test
    fun walkChain_returnsClustersInOrder() {
        val fat = freshFat()
        FatTable.write12(fat, 2, 3)
        FatTable.write12(fat, 3, 4)
        FatTable.write12(fat, 4, FatTable.EOC)
        val chain = FatTable.walkChain(fat, 2)
        assertEquals(listOf(2, 3, 4), chain)
    }

    @Test
    fun walkChain_singleClusterChain() {
        val fat = freshFat()
        FatTable.write12(fat, 5, FatTable.EOC)
        assertEquals(listOf(5), FatTable.walkChain(fat, 5))
    }

    @Test
    fun freeChain_zerosAllEntriesAndReturnsCount() {
        val fat = freshFat()
        FatTable.write12(fat, 2, 3)
        FatTable.write12(fat, 3, 7)
        FatTable.write12(fat, 7, FatTable.EOC)
        val freed = FatTable.freeChain(fat, 2)
        assertEquals(3, freed)
        assertEquals(FatTable.FREE, FatTable.read12(fat, 2))
        assertEquals(FatTable.FREE, FatTable.read12(fat, 3))
        assertEquals(FatTable.FREE, FatTable.read12(fat, 7))
    }

    @Test
    fun freeChain_idempotentOnFreshFat() {
        val fat = freshFat()
        assertEquals(0, FatTable.freeChain(fat, 5))
    }

    @Test
    fun walkChain_throwsOnLoop() {
        val fat = freshFat()
        FatTable.write12(fat, 2, 3)
        FatTable.write12(fat, 3, 2)
        try {
            FatTable.walkChain(fat, 2, maxClusters = 100)
            fail("Expected IllegalStateException for chain loop")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("FAT corruption"))
        }
    }

    // === allocator ===

    @Test
    fun countFreeClusters_freshFatReportsAllFree() {
        val fat = freshFat()
        assertEquals(100, FatTable.countFreeClusters(fat, totalClusters = 100))
    }

    @Test
    fun allocateChain_contiguousFromCluster2() {
        val fat = freshFat()
        val chain = FatTable.allocateChain(fat, clusterCount = 5, totalClusters = 100)
        assertEquals(listOf(2, 3, 4, 5, 6), chain)
        assertEquals(3, FatTable.read12(fat, 2))
        assertEquals(4, FatTable.read12(fat, 3))
        assertEquals(5, FatTable.read12(fat, 4))
        assertEquals(6, FatTable.read12(fat, 5))
        assertEquals(FatTable.EOC, FatTable.read12(fat, 6))
    }

    @Test
    fun allocateChain_skipsAlreadyAllocated() {
        val fat = freshFat()
        FatTable.write12(fat, 3, FatTable.EOC)
        val chain = FatTable.allocateChain(fat, clusterCount = 3, totalClusters = 100)
        assertEquals(listOf(2, 4, 5), chain)
        assertEquals(4, FatTable.read12(fat, 2))
        assertEquals(5, FatTable.read12(fat, 4))
        assertEquals(FatTable.EOC, FatTable.read12(fat, 5))
        assertEquals(FatTable.EOC, FatTable.read12(fat, 3))
    }

    @Test
    fun allocateChain_throwsNoFreeSpaceWhenInsufficient() {
        val fat = freshFat()
        for (n in 2..50) FatTable.write12(fat, n, FatTable.EOC)
        try {
            FatTable.allocateChain(fat, clusterCount = 10, totalClusters = 50)
            fail("Expected NoFreeSpaceException")
        } catch (e: NoFreeSpaceException) {
            assertTrue(e.message!!.isNotEmpty(), "must include need/free fields")
        }
    }

    @Test
    fun reservedEntries_areReadCorrectly() {
        val fat = freshFat()
        assertEquals(0xFF8, FatTable.read12(fat, 0))
        assertEquals(0xFFF, FatTable.read12(fat, 1))
    }
}
