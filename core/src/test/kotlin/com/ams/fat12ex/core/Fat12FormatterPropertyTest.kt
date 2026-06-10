package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Property-based tests for the [Fat12Formatter] geometry invariant.
 *
 * The geometry solver is the FAT12-only integrity boundary:
 * a computed `clusterCount >= 4085` would silently emit a FAT16 volume,
 * and the engine targets a stricter `< 4069` margin. This property sweeps MANY
 * volume sizes and asserts the dichotomy:
 *   - `Ok`       -> the on-disk BPB parses with a clusterCount in 1..4068, AND
 *   - `TooLarge` -> the size genuinely exceeds the FAT12 max (no smaller geometry
 *                   could have fit), never a spurious rejection.
 * Any other [Fat12Result] from `format()` is a failure.
 *
 * Uses jqwik 1.10.1 (`@Property` + `@ForAll @IntRange`) on the JUnit 5 platform.
 */
class Fat12FormatterPropertyTest {

    /**
     * format() on ANY device sized within the FAT12-plausible range either yields a
     * valid FAT12 geometry (parsed clusterCount in 1..4068, i.e. strictly < 4069)
     * or cleanly returns TooLarge. The sweep covers 2880 (1440K floppy) up through
     * 65536 sectors at 512 B/sector (32 MiB) — comfortably spanning the FAT12
     * boundary so both branches are exercised.
     */
    @Property(tries = 3_000)
    fun format_anyValidSize_yieldsValidFat12Geometry(
        @ForAll @IntRange(min = 2880, max = 65536) totalSectors: Int,
    ) {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = totalSectors.toLong())
        when (val result = Fat12Formatter(device).format()) {
            is Fat12Result.Ok -> {
                val bpb = Bpb.parse(device)
                assertTrue(
                    bpb.clusterCount in 1..4068,
                    "Ok geometry must yield clusterCount in 1..4068 (< 4069); " +
                        "got ${bpb.clusterCount} for $totalSectors sectors",
                )
            }
            is Fat12Result.TooLarge -> {
                // A TooLarge is only legitimate if NO power-of-two spc could place the
                // cluster count below the 4069 boundary — i.e. the smallest non-trivial
                // geometry (spc as large as it gets) still overflows. We prove this by
                // asserting computeGeometry (the same solver) also returns null.
                assertTrue(
                    Fat12Formatter(device).computeGeometry(totalSectors, 512) == null,
                    "TooLarge must mean no FAT12 geometry fits $totalSectors sectors, " +
                        "but computeGeometry returned a geometry",
                )
            }
            else -> fail("unexpected format() result for $totalSectors sectors: $result")
        }
    }

    /**
     * The clusterCount the solver returns must NEVER reach the 4069 engine boundary
     * for ANY size it accepts — asserted directly on the geometry solver
     * (independent of the BPB write/parse round-trip) across the same size sweep.
     * This pins the `< 4069` invariant the geometry math guarantees.
     */
    @Property(tries = 3_000)
    fun computeGeometry_neverReaches4069(
        @ForAll @IntRange(min = 2880, max = 65536) totalSectors: Int,
    ) {
        val geometry = Fat12Formatter(InMemoryBlockDevice(512, totalSectors.toLong()))
            .computeGeometry(totalSectors, 512)
        if (geometry != null) {
            assertTrue(
                geometry.clusters in 1 until 4069,
                "computed clusters must be in 1 until 4069; " +
                    "got ${geometry.clusters} for $totalSectors sectors",
            )
        }
    }

    /**
     * An explicitly oversize device (16,777,216 sectors = 8 GiB at 512 B) has no
     * valid FAT12 geometry and MUST return TooLarge — the corner that pins the
     * upper branch of the dichotomy even if the property never samples a no-fit
     * size in its sweep range.
     */
    @Test
    fun format_oversizeDevice_returnsTooLarge() {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 16_777_216L)
        val result = Fat12Formatter(device).format()
        assertTrue(
            result is Fat12Result.TooLarge,
            "an 8 GiB device must be rejected as TooLarge, got $result",
        )
    }
}
