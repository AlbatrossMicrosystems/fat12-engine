package com.ams.fat12ex.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FAT12 BIOS Parameter Block parser.
 *
 * Reads sector 0 of the partition (the boot sector / BPB), determines the FAT
 * type by CLUSTER COUNT (per the Microsoft FAT spec: < 4085 clusters = FAT12),
 * and exposes the BPB fields the rest of the FAT layer needs.
 *
 * On any non-FAT12 BPB, [parse] throws [NotFat12Exception] carrying geometry
 * diagnostics (cluster count + BS_FilSysType label). The cluster-count gate is
 * the FAT16/FAT32 rejection contract — a volume with >= 4085 data clusters is
 * rejected here before any field is trusted.
 *
 * NOTE: BS_FilSysType at offset 0x36 is informational ONLY and must NOT be used
 * to determine FAT type. The FAT spec says so, and real-world FAT12 media labels
 * its volume "FAT     " (not "FAT12   "), which an ASCII-label gate would wrongly
 * reject. Cluster count is the binding check; the label is kept only for
 * diagnostics.
 *
 * All offsets are PARTITION-RELATIVE byte offsets.
 */
data class Bpb(
    val bytsPerSec: Int,
    val secPerClus: Int,
    val rsvdSecCnt: Int,
    val numFATs: Int,
    val rootEntCnt: Int,
    val totSec: Int,
    val fatSz: Int,
) {
    val clusterSize: Int = bytsPerSec * secPerClus
    val fat0Offset: Long = rsvdSecCnt.toLong() * bytsPerSec
    val fat1Offset: Long = (rsvdSecCnt + fatSz).toLong() * bytsPerSec
    val rootDirOffset: Long = (rsvdSecCnt + numFATs * fatSz).toLong() * bytsPerSec
    val rootDirBytes: Int = rootEntCnt * 32
    val rootDirSectors: Int = (rootDirBytes + bytsPerSec - 1) / bytsPerSec
    val dataAreaOffset: Long = rootDirOffset + rootDirSectors.toLong() * bytsPerSec
    val fatBytes: Int = fatSz * bytsPerSec

    val clusterCount: Int = run {
        val dataSec = totSec - (rsvdSecCnt + numFATs * fatSz + rootDirSectors)
        if (secPerClus == 0) 0 else dataSec / secPerClus
    }

    fun byteOffsetOfCluster(cluster: Int): Long {
        require(cluster >= 2) { "Cluster numbers start at 2 in FAT12; got $cluster" }
        return dataAreaOffset + (cluster - 2).toLong() * clusterSize
    }

    companion object {
        const val FS_TYPE_OFFSET: Int = 0x36
        const val FS_TYPE_LEN: Int = 8
        const val FS_TYPE_FAT12: String = "FAT12   "

        /** FAT spec: a volume with fewer than 4085 data clusters is FAT12. */
        const val FAT12_MAX_CLUSTERS: Int = 4085

        const val BYTSPERSEC_OFFSET: Int = 0x0B
        const val SECPERCLUS_OFFSET: Int = 0x0D
        const val RSVDSECCNT_OFFSET: Int = 0x0E
        const val NUMFATS_OFFSET: Int = 0x10
        const val ROOTENTCNT_OFFSET: Int = 0x11
        const val TOTSEC16_OFFSET: Int = 0x13
        const val FATSZ16_OFFSET: Int = 0x16
        const val TOTSEC32_OFFSET: Int = 0x20
        const val BOOT_SIG_OFFSET: Int = 0x1FE

        fun parse(device: BlockDevice): Bpb {
            val sectorSize = device.blockSize
            val buffer = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
            device.read(0L, buffer)
            val bytes = buffer.array()

            val bytsPerSec = u16(bytes, BYTSPERSEC_OFFSET)
            val secPerClus = u8(bytes, SECPERCLUS_OFFSET)
            val rsvdSecCnt = u16(bytes, RSVDSECCNT_OFFSET)
            val numFATs = u8(bytes, NUMFATS_OFFSET)
            val rootEntCnt = u16(bytes, ROOTENTCNT_OFFSET)
            val totSec16 = u16(bytes, TOTSEC16_OFFSET)
            val fatSz16 = u16(bytes, FATSZ16_OFFSET)
            val totSec32 = u32(bytes, TOTSEC32_OFFSET)
            val totSec = if (totSec16 != 0) totSec16 else totSec32

            val bpb = Bpb(
                bytsPerSec = bytsPerSec,
                secPerClus = secPerClus,
                rsvdSecCnt = rsvdSecCnt,
                numFATs = numFATs,
                rootEntCnt = rootEntCnt,
                totSec = totSec,
                fatSz = fatSz16,
            )

            // FAT type is determined by CLUSTER COUNT per the Microsoft FAT spec
            // (< 4085 data clusters = FAT12). BS_FilSysType is read for diagnostics
            // only — NOT trusted (real-world FAT12 media labels its volume "FAT     ").
            // This gate is the FAT16/FAT32 rejection contract: a volume with more than
            // 4084 data clusters is rejected here.
            val fsTypeLabel = String(bytes, FS_TYPE_OFFSET, FS_TYPE_LEN, Charsets.US_ASCII).trimEnd()
            if (bytsPerSec == 0 || bpb.clusterCount < 1 || bpb.clusterCount >= FAT12_MAX_CLUSTERS) {
                throw NotFat12Exception(
                    actualFsType = fsTypeLabel,
                    hint = "volume reports ${bpb.clusterCount} data clusters " +
                        "(FAT12 requires 1..${FAT12_MAX_CLUSTERS - 1}); " +
                        "BS_FilSysType label='$fsTypeLabel'",
                )
            }

            return bpb
        }

        private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                    ((b[off + 1].toInt() and 0xFF) shl 8) or
                    ((b[off + 2].toInt() and 0xFF) shl 16) or
                    ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
