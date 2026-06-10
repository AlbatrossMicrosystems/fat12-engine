package com.ams.fat12ex.core

import java.nio.ByteBuffer

/**
 * Block-aligned I/O helpers — the single chokepoint every FAT region/cluster/
 * directory read or write funnels through.
 *
 * A [BlockDevice] only transfers WHOLE, block-aligned sectors (the USB MSC
 * backend physically cannot do a sub-sector or non-block-aligned transfer — see
 * [OffsetBlockDevice] / [com.ams.fat12ex.usb.UsbBlockDevice], where the device
 * offset is an LBA produced by integer-dividing the byte offset by `blockSize`).
 * These helpers let the rest of the engine read or write an arbitrary byte range
 * without worrying about sector alignment.
 *
 * ## Why this is a read-modify-write, not a zero-pad
 *
 * The original port ([readBytes]/[writeBytes], ported VERBATIM from the source
 * engine) assumed `device.blockSize == bytsPerSec`. Under that assumption, every
 * FAT region begins on a block boundary and zero-padding the trailing partial
 * block was harmless because the pad bytes landed past the end of the device's
 * meaningful data.
 *
 * That assumption is FALSE on 4Kn media (a 4096-byte-logical-sector LUN carrying
 * a 512-byte-sector FAT12: `device.blockSize == 4096` while `bytsPerSec == 512`).
 * There, a 512-byte boot-sector write at offset 0 (`setVolumeLabel`) was zero-
 * padded to 4096 bytes and written whole — clobbering disk bytes 512..4095, which
 * is the START of FAT #0. The FAT was zeroed, the allocation destroyed, yet the
 * boot sector stayed intact so the volume still mounted.
 *
 * The fix: both helpers operate on the block-aligned WINDOW enclosing the
 * requested byte range, and [writeBytes] READS that window first, splices the
 * caller's bytes into their exact position, and writes the whole window back.
 * Bytes outside `[byteOffset, byteOffset + bytes.size)` are preserved byte-for-
 * byte. Every device transfer is therefore whole-block AND block-aligned, so the
 * LBA division in the device layer is always exact (no truncation), and no real
 * data is ever overwritten with padding.
 *
 * Public signatures are unchanged: this protects EVERY write path
 * (setVolumeLabel, createFile, mkdir, rename, delete, writeFile, setAttributes).
 */

/** Read [length] bytes at [byteOffset], transferring only whole, block-aligned sectors. */
fun readBytes(device: BlockDevice, byteOffset: Long, length: Int): ByteArray {
    require(byteOffset >= 0) { "byteOffset must be >= 0, got $byteOffset" }
    require(length >= 0) { "length must be >= 0, got $length" }
    if (length == 0) return ByteArray(0)

    val blockSize = device.blockSize
    val blockStart = (byteOffset / blockSize) * blockSize
    val intoWindow = (byteOffset - blockStart).toInt()
    val windowLen = roundUpToBlock(intoWindow + length, blockSize)

    val buf = ByteBuffer.allocate(windowLen)
    device.read(blockStart, buf)
    return buf.array().copyOfRange(intoWindow, intoWindow + length)
}

/**
 * Write [bytes] at [byteOffset] via read-modify-write, transferring only whole,
 * block-aligned sectors and preserving every byte outside the written range.
 */
fun writeBytes(device: BlockDevice, byteOffset: Long, bytes: ByteArray) {
    require(byteOffset >= 0) { "byteOffset must be >= 0, got $byteOffset" }
    if (bytes.isEmpty()) return

    val blockSize = device.blockSize
    val blockStart = (byteOffset / blockSize) * blockSize
    val intoWindow = (byteOffset - blockStart).toInt()
    val windowLen = roundUpToBlock(intoWindow + bytes.size, blockSize)

    // Read-modify-write: pull the enclosing window so the partial leading/trailing
    // blocks keep their existing on-disk bytes, then splice the caller's bytes in.
    // (When the range exactly covers whole blocks the read is still correct; it just
    // reads bytes that are about to be fully overwritten.)
    val window = ByteArray(windowLen)
    if (intoWindow != 0 || windowLen != bytes.size) {
        val readBuf = ByteBuffer.allocate(windowLen)
        device.read(blockStart, readBuf)
        readBuf.array().copyInto(window)
    }
    bytes.copyInto(window, intoWindow)

    device.write(blockStart, ByteBuffer.wrap(window))
}

private fun roundUpToBlock(length: Int, blockSize: Int): Int =
    ((length + blockSize - 1) / blockSize) * blockSize
