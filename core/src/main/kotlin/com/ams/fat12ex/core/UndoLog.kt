package com.ams.fat12ex.core

import java.nio.ByteBuffer

/**
 * In-memory sector pre-image undo log — the atomicity primitive every
 * multi-step write op (`writeFile`, mkdir, rename, delete) reuses.
 *
 * Generalizes the source `FatFlasher.rollbackChain()` (which restored only the
 * FAT) into a uniform pre-image journal: before the FIRST write to any sector
 * within an operation, the caller invokes [captureIfAbsent] to snapshot that
 * sector's current bytes. On any failure (IOException, verify mismatch, disk
 * full) the caller invokes [rollback], which writes every captured pre-image
 * back to the device — restoring the volume to its exact pre-operation state.
 * On success the caller invokes [commit], which discards the journal.
 *
 * Usage discipline (the INT-02 contract):
 *   undoLog.captureIfAbsent(sectorOffset, sectorSize)   // BEFORE every device.write
 *   ... device.write(sectorOffset, ...) ...
 *   // on failure -> undoLog.rollback()  ; on success -> undoLog.commit()
 *
 * [captureIfAbsent] records a sector ONCE per op (the first, true pre-image): a
 * later mutation of an already-captured sector must NOT overwrite the snapshot,
 * or rollback would restore a half-written state. Pre-images are held in a
 * [linkedMapOf] so rollback replays them in deterministic insertion order.
 */
class UndoLog(private val device: BlockDevice) {

    // insertion order = deterministic rollback order
    private val preImages = linkedMapOf<Long, ByteArray>()

    /**
     * Snapshot the [sectorSize] bytes at [sectorByteOffset] iff this offset has
     * not already been captured in the current operation. Idempotent per offset:
     * the FIRST capture wins, so a subsequent device mutation cannot corrupt the
     * stored pre-image.
     */
    fun captureIfAbsent(sectorByteOffset: Long, sectorSize: Int) {
        if (sectorByteOffset !in preImages) {
            val buf = ByteBuffer.allocate(sectorSize)
            device.read(sectorByteOffset, buf)
            preImages[sectorByteOffset] = buf.array().copyOf()
        }
    }

    /**
     * Restore every captured pre-image to the device (in insertion order) and
     * clear the journal. After this the device bytes equal the pre-capture bytes
     * for every touched sector — the INT-02 byte-for-byte rollback.
     */
    fun rollback() {
        for ((offset, image) in preImages) {
            device.write(offset, ByteBuffer.wrap(image.copyOf()))
        }
        preImages.clear()
    }

    /** Discard the journal without writing (the operation succeeded). */
    fun commit() = preImages.clear()
}
