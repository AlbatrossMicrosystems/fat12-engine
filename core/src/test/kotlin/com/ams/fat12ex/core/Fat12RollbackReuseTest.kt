package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HR-01 regression — strict byte-for-byte rollback when a write REUSES clusters
 * freed by a prior delete (01-REVIEW.md HR-01, High).
 *
 * The two Plan-06 INT-02 proofs and the three Plan-08 compound-op proofs all roll
 * back on a freshly-formatted (all-zero) volume, where the pre-image of a
 * newly-allocated cluster is coincidentally zero — so the original
 * "zero-on-rollback" strategy looked correct. This test exercises the case the
 * suite never covered:
 *
 *   write A (non-zero, >= 2 clusters) -> delete A (clusters FREE but still hold A's
 *   residual bytes) -> write B reusing A's freed clusters, with a mid-op write
 *   failure on the second data cluster.
 *
 * On any formatted-but-USED volume the TRUE pre-image of B's reused clusters is
 * A's residual bytes, NOT zeros. Rollback must restore those residual bytes
 * byte-for-byte (INT-02), not zero the cluster. Against the original engine this
 * test FAILS (cluster 2 is zeroed on rollback, diverging from A's residual 0xA5
 * fill); after the fix it PASSES.
 */
class Fat12RollbackReuseTest {

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    @Test
    fun writeFile_midWriteFailure_intoReusedClusters_restoresResidualBytes_notZeros() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)
        val bpb = Bpb.parse(device)
        val cs = vol.clusterSize()

        // ---- 1. write file A with a recognizable NON-ZERO payload over >= 2 clusters.
        // The allocator picks the lowest free clusters (2, 3) for A's 2-cluster chain.
        val payloadA = ByteArray(2 * cs) { 0xA5.toByte() }
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/A.BIN", sequenceOf(payloadA)),
            "precondition: file A must write successfully",
        )

        // A occupies clusters 2 and 3 — confirm A's bytes are physically present.
        val clusterA0 = bpb.byteOffsetOfCluster(2)
        val clusterA1 = bpb.byteOffsetOfCluster(3)
        assertArrayEquals(payloadA.copyOfRange(0, cs), readBytes(device, clusterA0, cs))
        assertArrayEquals(payloadA.copyOfRange(cs, 2 * cs), readBytes(device, clusterA1, cs))

        // ---- 2. delete A. Its FAT entries go FREE but the data bytes are NOT zeroed
        // (delete only touches the FAT + the directory slot) — clusters 2,3 still hold 0xA5.
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.delete("/A.BIN"),
            "precondition: file A must delete successfully",
        )
        // The residual 0xA5 bytes survive the delete (proving the reuse scenario is real).
        assertArrayEquals(
            ByteArray(cs) { 0xA5.toByte() },
            readBytes(device, clusterA0, cs),
            "delete must NOT zero the data bytes — cluster 2 still holds A's residual fill",
        )

        // ---- 3. snapshot the TRUE pre-image (contains A's residual bytes in the now-free clusters).
        val snapshot = device.bytes.copyOf()

        // ---- 4. attempt to write file B into the reused clusters with a mid-op failure
        // on the SECOND data cluster (cluster 3). B reuses clusters 2,3 (lowest free).
        // The write to cluster 2 lands first (B's 0x5C bytes), then the write to cluster 3 throws.
        device.failNextWriteAt(bpb.byteOffsetOfCluster(3))
        val payloadB = ByteArray(2 * cs) { 0x5C.toByte() }

        val ex = assertThrows<FatWriteFailedException> {
            vol.writeFile("/B.BIN", sequenceOf(payloadB))
        }
        assertTrue(ex.cause != null, "FatWriteFailedException must wrap the underlying IOException")

        // ---- 5. INT-02: the volume must be byte-for-byte identical to its pre-B state —
        // i.e. cluster 2 holds A's residual 0xA5 bytes again, NOT zeros.
        assertArrayEquals(
            snapshot,
            device.bytes,
            "INT-02/HR-01: rollback must restore the TRUE pre-image (A's residual bytes) of reused clusters, not zero them",
        )
        // And B must not be committed to the directory.
        val entries = (vol.list("/") as Fat12Result.Ok).value
        assertTrue(entries.none { it.shortName == "B       BIN" }, "no dir entry may be committed after rollback")
    }
}
