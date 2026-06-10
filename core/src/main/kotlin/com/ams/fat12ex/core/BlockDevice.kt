package com.ams.fat12ex.core

import java.nio.ByteBuffer

/**
 * The single storage seam for the FAT12 engine. Every backend (in-memory test
 * harness, USB-OTG via libaums in :app) implements exactly this. Replaces
 * libaums' BlockDeviceDriver so :core carries zero Android/libaums imports.
 *
 * Offsets passed to read/write are PARTITION-RELATIVE byte offsets.
 */
interface BlockDevice {
    val blockSize: Int
    val blocks: Long
    fun init()
    fun read(byteOffset: Long, dest: ByteBuffer)
    fun write(byteOffset: Long, src: ByteBuffer)
}
