package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Fuzz tests: the read path must reject hostile / random bytes CLEANLY —
 * no crash, no out-of-bounds read, no unexpected exception type. The fuzz harness
 * IS the adversary (threat boundary "hostile/random bytes → read path"):
 *
 *   - [Bpb.parse] on a random 512-byte sector may legitimately succeed (random
 *     bytes CAN form a valid BPB) or reject with the typed [NotFat12Exception];
 *     ANY other exception type (IndexOutOfBounds / NPE / arithmetic) is a defect
 *     (theFAT16/32-rejection robustness the USB mount path depends on).
 *   - [DirRegion.walk] must NEVER throw on ANY 2048-byte buffer, including a
 *     pathological all-0x0F (ATTR_LFN) buffer with no terminating short entry
 *     (thebounded-loop guarantee a hostile directory cannot
 *     crash or hang).
 *
 * Reproducibility: each iteration derives its bytes from a per-iteration seed
 * computed from a fixed [BASE_SEED]; on failure the seed is printed in the failure
 * message so the exact crashing input can be regenerated deterministically.
 *
 * JUnit 5 @RepeatedTest.
 */
class FatFuzzTest {

    /**
     * 1000 random 512-byte sectors fed to [Bpb.parse]. Success is permitted (random
     * bytes may chance to describe a valid FAT12 BPB); [NotFat12Exception] is the
     * permitted typed rejection. Any OTHER exception type fails the test — proving
     * the BPB parser fails typed-and-clean on adversarial volume bytes (T-01-21).
     */
    @RepeatedTest(1000)
    fun bpbFuzz_randomBytes_onlyNotFat12ExceptionPermitted(info: org.junit.jupiter.api.RepetitionInfo) {
        val seed = BASE_SEED + info.currentRepetition
        val rng = Random(seed)
        val sector = ByteArray(512) { rng.nextInt().toByte() }
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 10L)
        sector.copyInto(device.bytes, 0)
        try {
            Bpb.parse(device)
            // Random bytes happened to form a valid (clusterCount 1..4084) BPB — fine.
        } catch (e: NotFat12Exception) {
            // Expected: clean typed rejection of a non-FAT12 / malformed sector.
        } catch (e: Throwable) {
            fail<Unit>(
                "Bpb.parse threw an unexpected exception on random bytes " +
                    "(seed=$seed, rep=${info.currentRepetition}): " +
                    "${e::class.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * 1000 random 2048-byte directory buffers fed to [DirRegion.walk]. walk MUST
     * return (possibly empty) and NEVER throw — pinning the T-01-11/T-01-22 bounded-
     * loop guarantee against a hostile directory region.
     */
    @RepeatedTest(1000)
    fun dirFuzz_randomDirBytes_walkNeverThrows(info: org.junit.jupiter.api.RepetitionInfo) {
        val seed = BASE_SEED + 1_000_000 + info.currentRepetition
        val rng = Random(seed)
        val dirBytes = ByteArray(2048) { rng.nextInt().toByte() }
        try {
            val result = DirRegion.walk(dirBytes)
            assertNotNull(result, "walk must return a (possibly empty) list, never null")
        } catch (e: Throwable) {
            fail<Unit>(
                "DirRegion.walk threw on random dir bytes " +
                    "(seed=$seed, rep=${info.currentRepetition}): " +
                    "${e::class.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * Pathological unterminated-LFN buffer: every 32-byte slot is an ATTR_LFN (0x0F)
     * entry with NO trailing short entry to bind the preamble run. walk must not read
     * past the buffer or throw — it should simply produce no completed entry. Guards
     * the unterminated-LFN-preamble edge (T-01-22).
     */
    @Test
    fun dirFuzz_oversizedLfnRun_doesNotReadOob() {
        // 2048 bytes = 64 slots, all 0x0F (ATTR_LFN at byte 0 AND at the 0x0B attr
        // position — byte 0 must be non-0x00/non-0xE5 so walk does not break/skip).
        val dirBytes = ByteArray(2048) { 0x0F }
        try {
            val result = DirRegion.walk(dirBytes)
            // No short entry ever terminates a run, so no WalkedEntry is produced.
            assertNotNull(result, "walk must return a list on an all-LFN buffer")
        } catch (e: Throwable) {
            fail<Unit>(
                "DirRegion.walk threw on an all-0x0F unterminated-LFN buffer: " +
                    "${e::class.simpleName}: ${e.message}",
            )
        }
    }

    companion object {
        /**
         * Fixed base seed for reproducibility. Each iteration uses BASE_SEED + rep
         * (BPB) or BASE_SEED + 1_000_000 + rep (dir) so the two fuzz streams are
         * disjoint and any discovered crash is regenerable from the printed seed.
         */
        const val BASE_SEED: Long = 0xFA712L
    }
}
