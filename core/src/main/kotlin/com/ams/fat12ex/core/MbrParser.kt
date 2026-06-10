package com.ams.fat12ex.core

/**
 * MBR boot-sector parser.
 *
 * For 4Kn disks (devices reporting 4096-byte native sectors, e.g. the
 * BrainFPV Radix 2 HD firmware LUN), MBR partition-entry LBA values are
 * interpreted in the device's native sector units, NOT the strict-spec
 * 512-byte units. This matches Windows USBSTOR's behavior on 4Kn disks
 * and confirmed against the BrainFPV Radix 2 HD hardware
 * (§3: partition offset 258,048 =
 * 63 × 4096, partition size 3,932,160 = 960 × 4096). Pass the device's
 * actual block size to [parseFirstPartition].
 *
 * Default `sectorSize=512` matches the standard MBR-spec interpretation
 * for 512n disks.
 */
object MbrParser {

    private const val BOOT_SIG_OFFSET = 510
    private const val BOOT_SIG_LO: Byte = 0x55.toByte()
    private const val BOOT_SIG_HI: Byte = 0xAA.toByte()

    private const val PART_TABLE_OFFSET = 0x1BE
    private const val PART_ENTRY_SIZE = 16
    private const val PART_ENTRY_COUNT = 4

    private const val ENTRY_TYPE_OFFSET = 4
    private const val ENTRY_START_LBA_OFFSET = 8
    private const val ENTRY_SECTOR_COUNT_OFFSET = 12

    const val DEFAULT_LBA_BYTES = 512L

    data class PartitionInfo(
        val byteOffset: Long,
        val byteLength: Long,
        val partitionType: Int,
    )

    /**
     * Parse [sector0] (must be ≥ 512 bytes) and return the first non-empty
     * primary partition entry, or null if the boot signature is absent or
     * all 4 entries are empty.
     *
     * @param sectorSize the device's native sector size in bytes. For
     *   standard 512n disks pass 512 (or omit). For 4Kn disks (e.g. the
     *   Radix 2 HD firmware LUN, which reports 4096-byte sectors via
     *   SCSI READ_CAPACITY) pass 4096 — see KDoc on [MbrParser].
     */
    fun parseFirstPartition(
        sector0: ByteArray,
        sectorSize: Int = DEFAULT_LBA_BYTES.toInt(),
    ): PartitionInfo? {
        require(sector0.size >= 512) {
            "MBR parse needs at least 512 bytes, got ${sector0.size}"
        }
        if (sector0[BOOT_SIG_OFFSET] != BOOT_SIG_LO ||
            sector0[BOOT_SIG_OFFSET + 1] != BOOT_SIG_HI
        ) {
            return null
        }
        val sectorBytes = sectorSize.toLong()
        for (i in 0 until PART_ENTRY_COUNT) {
            val entryStart = PART_TABLE_OFFSET + i * PART_ENTRY_SIZE
            val type = sector0[entryStart + ENTRY_TYPE_OFFSET].toInt() and 0xFF
            if (type == 0) continue
            val startLba = readLeU32(sector0, entryStart + ENTRY_START_LBA_OFFSET)
            val sectorCount = readLeU32(sector0, entryStart + ENTRY_SECTOR_COUNT_OFFSET)
            return PartitionInfo(
                byteOffset = startLba * sectorBytes,
                byteLength = sectorCount * sectorBytes,
                partitionType = type,
            )
        }
        return null
    }

    private fun readLeU32(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)
    }
}
