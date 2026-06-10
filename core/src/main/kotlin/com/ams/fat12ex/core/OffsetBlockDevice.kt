package com.ams.fat12ex.core

import java.nio.ByteBuffer

/**
 * [BlockDevice] adapter that re-bases all reads and writes by [byteOffset].
 *
 * Used by `MscFlasher.pickFirmwareLun` to expose a partition's byte range as a
 * self-contained block device without going through libaums' partition-table
 * layer (which mis-handles devices reporting non-512-byte logical sectors).
 *
 * @param underlying the per-LUN block device obtained from libaums setupDevice
 * @param byteOffset partition's byte offset within the LUN, in 512-byte LBA units
 * @param byteLength partition's byte length, used to clamp [blocks]; if non-positive,
 *   falls through to the underlying device's remaining capacity past [byteOffset]
 */
class OffsetBlockDevice(
    private val underlying: BlockDevice,
    val byteOffset: Long,
    private val byteLength: Long,
) : BlockDevice {

    override val blockSize: Int
        get() = underlying.blockSize

    override val blocks: Long
        get() {
            if (byteLength <= 0) {
                return underlying.blocks - (byteOffset / underlying.blockSize)
            }
            return byteLength / underlying.blockSize
        }

    override fun init() {
        // underlying is already init'd by libaums setupDevice; no-op here.
    }

    // OffsetBlockDevice presents a BYTE-addressed interface to the FAT writer
    // (FatFlasher/Bpb pass byte offsets), but the underlying device driver
    // takes the device offset in BLOCKS (LBA) — see ScsiBlockDevice KDoc "the
    // devOffset is not in bytes!". So translate: add the partition's byte start
    // (this.byteOffset) to the requested byte offset, then divide by blockSize to get
    // the absolute LBA. A regression once passed the raw byte sum as the device offset,
    // so e.g. a BPB read at byteOffset 258048 became LBA 258048 (out of range on a
    // 1022-block LUN) → READ(10) STALL (-9 EPIPE). Root-caused via a Windows-vs-host
    // USB CBW comparison (Windows used LBA 0x3F=63; the host emitted LBA 0x3F000=258048).
    // byteOffset + this.byteOffset must be block-aligned (FAT regions are).
    override fun read(byteOffset: Long, dest: ByteBuffer) {
        underlying.read((byteOffset + this.byteOffset) / underlying.blockSize, dest)
    }

    override fun write(byteOffset: Long, src: ByteBuffer) {
        underlying.write((byteOffset + this.byteOffset) / underlying.blockSize, src)
    }
}
