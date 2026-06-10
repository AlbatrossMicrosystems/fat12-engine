package com.ams.fat12ex.core.testutil

import com.ams.fat12ex.core.BlockDevice
import java.io.IOException
import java.nio.ByteBuffer

/**
 * In-memory [BlockDeviceDriver] for unit tests.
 *
 * Backing storage is a `ByteArray` of size `blocks * blockSize`. read/write
 * operate at byte-offset granularity; libaums-compatible.
 *
 * Optional fault-injection hooks support negative-path tests:
 * - [failNextWriteAt]: throw [IOException] on the next write covering the offset
 * - [corruptOnReadAt]: XOR-mask the byte at the offset on the next read covering it
 *
 * Hooks are one-shot. Records ordered call log via [callLog] for assertions
 * about write-order (e.g. directory entry was written LAST per RDX-FAT-04).
 */
class InMemoryBlockDevice(
    override val blockSize: Int = 512,
    override val blocks: Long = 7680L,
) : BlockDevice {

    val bytes: ByteArray = ByteArray((blocks * blockSize).toInt())

    sealed interface CallEntry {
        data class Read(val offset: Long, val length: Int) : CallEntry
        data class Write(val offset: Long, val length: Int) : CallEntry
    }

    val callLog: MutableList<CallEntry> = mutableListOf()

    private var failWriteAtOffset: Long? = null
    private var corruptReadAtOffset: Long? = null
    private var corruptReadMask: Byte = 0

    override fun init() { /* no-op for in-memory device */ }

    override fun read(byteOffset: Long, dest: ByteBuffer) {
        val len = dest.remaining()
        require(byteOffset >= 0 && byteOffset + len <= bytes.size) {
            "read out of range: offset=$byteOffset len=$len size=${bytes.size}"
        }
        callLog.add(CallEntry.Read(byteOffset, len))
        val startPos = dest.position()
        dest.put(bytes, byteOffset.toInt(), len)
        // Optional fault: corrupt the byte at corruptReadAtOffset if covered.
        val cor = corruptReadAtOffset
        if (cor != null && cor in byteOffset until (byteOffset + len)) {
            val bufIndex = startPos + (cor - byteOffset).toInt()
            if (dest.hasArray()) {
                val arr = dest.array()
                val arrIndex = dest.arrayOffset() + bufIndex
                arr[arrIndex] = (arr[arrIndex].toInt() xor corruptReadMask.toInt()).toByte()
            } else {
                val origLimit = dest.limit()
                val origPos = dest.position()
                dest.position(bufIndex)
                val orig = dest.get(bufIndex)
                dest.put(bufIndex, (orig.toInt() xor corruptReadMask.toInt()).toByte())
                dest.position(origPos)
                dest.limit(origLimit)
            }
            corruptReadAtOffset = null
            corruptReadMask = 0
        }
    }

    override fun write(byteOffset: Long, src: ByteBuffer) {
        val len = src.remaining()
        require(byteOffset >= 0 && byteOffset + len <= bytes.size) {
            "write out of range: offset=$byteOffset len=$len size=${bytes.size}"
        }
        // Optional fault: throw if this write covers the configured offset.
        val fail = failWriteAtOffset
        if (fail != null && fail in byteOffset until (byteOffset + len)) {
            failWriteAtOffset = null
            throw IOException("InMemoryBlockDevice: simulated write failure at offset $fail")
        }
        callLog.add(CallEntry.Write(byteOffset, len))
        src.get(bytes, byteOffset.toInt(), len)
    }

    /** Throw [IOException] from the next [write] that covers [byteOffset]. One-shot. */
    fun failNextWriteAt(byteOffset: Long) { failWriteAtOffset = byteOffset }

    /** XOR-mask the byte at [byteOffset] on the next [read] that covers it. One-shot. */
    fun corruptOnReadAt(byteOffset: Long, mask: Byte) {
        corruptReadAtOffset = byteOffset
        corruptReadMask = mask
    }
}
