package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * OffsetBlockDevice presents a BYTE-addressed interface (the FAT writer thinks in
 * bytes) but its underlying device takes device offsets in BLOCKS (LBA) — "the
 * devOffset is not in bytes!" (ScsiBlockDevice KDoc). These tests assert the
 * byte→LBA translation: absolute LBA = (requestByteOffset + partitionByteOffset)
 * / blockSize. (Regression guard for the LBA-63 device-offset stall, where
 * the raw byte offset 258048 was sent as LBA 258048.)
 */
class OffsetBlockDeviceTest {

    @Test
    fun read_translatesPartitionByteOffsetToLba() {
        val underlying = InMemoryBlockDevice(blockSize = 512, blocks = 64L)
        // Partition starts at byte 4096 = LBA 8 (4096 / 512). View byte 0 → absolute LBA 8.
        val view = OffsetBlockDevice(underlying, byteOffset = 4096L, byteLength = 4096L)
        val buf = ByteBuffer.allocate(512)
        view.read(0L, buf)
        val read = underlying.callLog.first() as InMemoryBlockDevice.CallEntry.Read
        assertEquals(8L, read.offset)
    }

    @Test
    fun read_translatesNonZeroByteOffsetToLba() {
        val underlying = InMemoryBlockDevice(blockSize = 512, blocks = 64L)
        val view = OffsetBlockDevice(underlying, byteOffset = 4096L, byteLength = 8192L)
        // 1024 bytes into the partition → (1024 + 4096) / 512 = LBA 10.
        view.read(1024L, ByteBuffer.allocate(512))
        val read = underlying.callLog.first() as InMemoryBlockDevice.CallEntry.Read
        assertEquals(10L, read.offset)
    }

    @Test
    fun write_translatesPartitionByteOffsetToLba() {
        val underlying = InMemoryBlockDevice(blockSize = 512, blocks = 64L)
        val view = OffsetBlockDevice(underlying, byteOffset = 4096L, byteLength = 4096L)
        view.write(0L, ByteBuffer.allocate(512))
        val write = underlying.callLog.first() as InMemoryBlockDevice.CallEntry.Write
        assertEquals(8L, write.offset)
    }

    @Test
    fun read_4kBlock_partitionAt258048IsLba63() {
        // The exact Radix case: 4096-byte blocks, firmware partition at byte 258048.
        val underlying = InMemoryBlockDevice(blockSize = 4096, blocks = 1023L)
        val view = OffsetBlockDevice(underlying, byteOffset = 258048L, byteLength = 3932160L)
        view.read(0L, ByteBuffer.allocate(4096))
        val read = underlying.callLog.first() as InMemoryBlockDevice.CallEntry.Read
        assertEquals(63L, read.offset) // 258048 / 4096 = 63 (NOT 258048)
    }

    @Test
    fun blockSize_forwardsFromUnderlying() {
        val underlying = InMemoryBlockDevice(blockSize = 4096, blocks = 1023L)
        val view = OffsetBlockDevice(underlying, byteOffset = 0L, byteLength = 0L)
        assertEquals(4096, view.blockSize)
    }

    @Test
    fun blocks_clampedToByteLength() {
        // Underlying is 1 MB (256 blocks of 4096); partition is the first 4 KB only.
        val underlying = InMemoryBlockDevice(blockSize = 4096, blocks = 256L)
        val view = OffsetBlockDevice(underlying, byteOffset = 0L, byteLength = 4096L)
        assertEquals(1L, view.blocks)
    }
}
