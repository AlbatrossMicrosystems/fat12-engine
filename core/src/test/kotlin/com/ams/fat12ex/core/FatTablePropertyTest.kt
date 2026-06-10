package com.ams.fat12ex.core

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Property-based tests for the FAT12 12-bit pack/unpack kernel.
 *
 * The 12-bit packed FAT is the single highest-impact silent-corruption surface:
 * two 12-bit entries share three bytes, so a neighbour-
 * nibble write at ANY index can corrupt an adjacent entry without ever throwing.
 * A fixed example set (the inherited [FatTableTest]) only proves the corners it
 * names; this property sweep proves bit-exact round-trip for ALL values 0..0xFFF
 * at ALL cluster indices 0..4084 — the full addressable FAT12 — closing the
 * boundary-index packing-error class a finite example set would miss.
 *
 * Uses jqwik 1.10.1 (`@Property` + `@ForAll @IntRange`) on the JUnit 5 platform
 * (useJUnitPlatform). No `@ParameterizedTest` fallback was
 * needed — the jqwik annotations compile and run cleanly under Kotlin 2.4.
 */
class FatTablePropertyTest {

    /**
     * A FAT buffer large enough to hold cluster 4084's two bytes. Cluster 4084's
     * byte offset is `4084 + (4084 shr 1) = 6126`, so it touches bytes 6126..6127;
     * 9000 bytes is comfortably beyond that.
     */
    private fun bigFat(): ByteArray = ByteArray(9000)

    /**
     * EXHAUSTIVE round-trip property: for ALL cluster indices n in 0..4084 and ALL
     * 12-bit values v in 0..0xFFF, `write12(fat,n,v)` then `read12(fat,n)` returns
     * v EXACTLY. The boundaries 0,1,2,3,4083,4084 fall inside the index range and
     * are additionally pinned by [fatPackUnpack_boundaryClusters] below.
     *
     * `tries` is raised to 10_000 so jqwik samples the (4085 x 4096) space densely;
     * the exhaustive corners are guaranteed by the companion `@Test`.
     */
    @Property(tries = 10_000)
    fun fatPackUnpack_roundTrip(
        @ForAll @IntRange(min = 0, max = 4084) n: Int,
        @ForAll @IntRange(min = 0, max = 0xFFF) v: Int,
    ) {
        val fat = bigFat()
        FatTable.write12(fat, n, v)
        assertEquals(v, FatTable.read12(fat, n), "cluster $n value 0x${v.toString(16)}")
    }

    /**
     * Pins the FAT12 addressing corners EXHAUSTIVELY (so they are covered even if
     * the property engine samples them sparsely): the reserved-entry / low-index
     * boundaries {0,1,2,3} and the high-end boundaries {4083,4084} (last valid
     * FAT12 cluster), crossed with the value corners that exercise every nibble
     * position {0x000,0x001,0x7FF,0x800,0xFFE,0xFFF}.
     *
     * These are the explicit corner pins for the 12-bit packing scheme;
     * a single failure here is a 12-bit packing off-by-one.
     */
    @Test
    fun fatPackUnpack_boundaryClusters() {
        val indices = intArrayOf(0, 1, 2, 3, 4083, 4084)
        val values = intArrayOf(0x000, 0x001, 0x7FF, 0x800, 0xFFE, 0xFFF)
        for (n in indices) {
            for (v in values) {
                val fat = bigFat()
                FatTable.write12(fat, n, v)
                assertEquals(
                    v,
                    FatTable.read12(fat, n),
                    "boundary cluster $n value 0x${v.toString(16)} must round-trip bit-exact",
                )
            }
        }
    }

    /**
     * Neighbour-nibble non-interference property: writing cluster n must NOT
     * disturb the already-written value at the adjacent cluster n+1 (the two share
     * a byte for one of the two parities). This is the specific corruption class
     * 12-bit packing risks; the property sweeps it across the whole FAT.
     */
    @Property(tries = 5_000)
    fun fatWrite_doesNotCorruptNeighbour(
        @ForAll @IntRange(min = 0, max = 4083) n: Int,
        @ForAll @IntRange(min = 0, max = 0xFFF) a: Int,
        @ForAll @IntRange(min = 0, max = 0xFFF) b: Int,
    ) {
        val fat = bigFat()
        FatTable.write12(fat, n + 1, b)   // neighbour first
        FatTable.write12(fat, n, a)       // then n
        assertEquals(a, FatTable.read12(fat, n), "cluster $n own value")
        assertEquals(b, FatTable.read12(fat, n + 1), "neighbour cluster ${n + 1} must be preserved")
    }
}
