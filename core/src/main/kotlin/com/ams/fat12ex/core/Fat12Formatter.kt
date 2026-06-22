package com.ams.fat12ex.core

import java.nio.ByteBuffer

/**
 * Production format-from-scratch for FAT12 volumes.
 *
 * Promotes the test-only `Fat12ImageBuilder` byte-layout knowledge into a
 * production formatter that accepts ANY [BlockDevice] and computes geometry
 * dynamically: it picks the SMALLEST power-of-two sectors-per-cluster that
 * yields `totalClusters` in `1 until 4069` (a conservative safety margin below
 * the 4085 FAT12 boundary that also minimizes slack), and returns
 * [Fat12Result.TooLarge] when no valid FAT12 geometry exists for the device's
 * sector count.
 *
 * On success it writes:
 *  - a valid BPB into sector 0 (boot jump, OEM, all geometry fields, media
 *    descriptor at 0x15, `BS_BootSig = 0x29` at offset 38, volume label at
 *    BPB offset 43, FilSysType "FAT12   " at 0x36, boot signature 0x55AA at
 *    510-511),
 *  - two byte-for-byte identical FAT copies whose reserved entries are
 *    `FAT[0] = (0xF00 | media)` and `FAT[1] = 0xFFF` (FAT[0] low byte
 *    mirrors the BPB media descriptor per the FAT spec), all other clusters
 *    free,
 *  - a zero-initialized root directory carrying a single VOLUME_ID entry
 *    (attr 0x08) holding the same 11-byte label.
 *
 * The geometry math (ceiling FAT-size, `< 4069` boundary) is the
 * corruption-prone surface the mkfs.fat golden fixtures cross-validate.
 *
 * Carries no Android or USB-library imports — pure JVM.
 */
class Fat12Formatter(private val device: BlockDevice) {

    /**
     * Computed FAT12 geometry for the target device.
     *
     * @param secPerClus      power-of-two sectors per cluster (1..128)
     * @param fatSz           sectors per FAT copy (ceiling-derived)
     * @param clusters        data cluster count (guaranteed `1 until 4069`)
     * @param rootDirSectors  sectors occupied by the root directory region
     */
    data class Geometry(
        val secPerClus: Int,
        val fatSz: Int,
        val clusters: Int,
        val rootDirSectors: Int,
    )

    /**
     * Format the device as FAT12 from scratch.
     *
     * @param volumeLabel     11-char volume label (space-padded). Written to
     *                        BPB offset 43 AND a root-dir VOLUME_ID entry.
     * @param volumeId        32-bit volume serial (BS_VolID at offset 39).
     * @param mediaDescriptor media byte written to BPB 0x15 and mirrored into
     *                        FAT[0]'s low byte (FAT spec). Defaults to 0xF8
     *                        (fixed disk); floppy geometries use 0xF0/0xF9/0xFD.
     * @return [Fat12Result.Ok] on success, or [Fat12Result.TooLarge] when no
     *         valid FAT12 geometry fits the device.
     */
    fun format(
        volumeLabel: String = "NO NAME    ",
        volumeId: Int = System.currentTimeMillis().toInt(),
        mediaDescriptor: Int = 0xF8,
    ): Fat12Result<Unit> {
        val totalSectors = device.blocks.toInt()
        val bytesPerSector = device.blockSize

        val geometry = computeGeometry(totalSectors, bytesPerSector)
            ?: return Fat12Result.TooLarge(actualBytes = device.blocks * bytesPerSector)
    /**
        * Never emit near-FAT16 geometry. The geometry loop already
        * bounds clusters to 1 until 4069, but assert before writing so a future
        * refactor cannot silently breach the FAT12-only integrity boundary.
        */
        check(geometry.clusters < 4069) {
            "computed clusterCount ${geometry.clusters} >= 4069 violates FAT12 boundary"
        }

        val label11 = volumeLabel.padEnd(11, ' ').take(11)

        writeBpb(totalSectors, bytesPerSector, geometry, label11, volumeId, mediaDescriptor)
        writeFats(bytesPerSector, geometry, mediaDescriptor)
        writeRootDir(bytesPerSector, geometry, label11)

        return Fat12Result.Ok(Unit)
    }

    /**
     * Compute the smallest power-of-two sectors-per-cluster that yields a valid
     * FAT12 cluster count (`1 until 4069`) for the given device, refining the
     * FAT size with ceiling division. Returns null if no spc fits, in
     * which case the caller returns [Fat12Result.TooLarge].
     */
    fun computeGeometry(totalSectors: Int, bytesPerSector: Int): Geometry? {
        val rsvdSecCnt = RSVD_SEC_CNT
        val numFATs = NUM_FATS
        val rootEntCnt = ROOT_ENT_CNT
        val rootDirSectors = (rootEntCnt * DIR_ENTRY_SIZE).ceilDiv(bytesPerSector)

        for (spc in listOf(1, 2, 4, 8, 16, 32, 64, 128)) {
            var fatSz = 1
            // 3 iterations converge for FAT12 (fatSz depends on clusters which
            // depend on fatSz). Ceiling FAT-size keeps the FAT large enough
            // to address the last cluster.
            repeat(3) {
                val dataSectors = totalSectors - rsvdSecCnt - numFATs * fatSz - rootDirSectors
                if (dataSectors <= 0) return@repeat
                val clusters = dataSectors / spc
                val fatBytes = ((clusters + 2) * 12).ceilDiv(8)
                fatSz = fatBytes.ceilDiv(bytesPerSector)
            }
            val dataSectors = totalSectors - rsvdSecCnt - numFATs * fatSz - rootDirSectors
            val clusters = if (dataSectors > 0) dataSectors / spc else 0
            if (clusters in 1 until 4069) {
                return Geometry(spc, fatSz, clusters, rootDirSectors)
            }
        }
        return null
    }

    private fun writeBpb(
        totalSectors: Int,
        bytesPerSector: Int,
        geometry: Geometry,
        label11: String,
        volumeId: Int,
        mediaDescriptor: Int,
    ) {
        val b = ByteArray(bytesPerSector)

        // Boot jump + OEM name.
        b[0] = 0xEB.toByte(); b[1] = 0x3C; b[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(b, 3)

        // BPB geometry fields (all at the named Bpb.*_OFFSET constants).
        u16(b, Bpb.BYTSPERSEC_OFFSET, bytesPerSector)
        b[Bpb.SECPERCLUS_OFFSET] = geometry.secPerClus.toByte()
        u16(b, Bpb.RSVDSECCNT_OFFSET, RSVD_SEC_CNT)
        b[Bpb.NUMFATS_OFFSET] = NUM_FATS.toByte()
        u16(b, Bpb.ROOTENTCNT_OFFSET, ROOT_ENT_CNT)
        u16(b, Bpb.TOTSEC16_OFFSET, if (totalSectors < 0x10000) totalSectors else 0)
        b[MEDIA_OFFSET] = (mediaDescriptor and 0xFF).toByte()
        u16(b, Bpb.FATSZ16_OFFSET, geometry.fatSz)
        u32(b, Bpb.TOTSEC32_OFFSET, if (totalSectors >= 0x10000) totalSectors else 0)

        // Extended boot record (BS_*): drive number, signature, volume serial,
        // volume label, filesystem type. BS_BootSig = 0x29 at offset 38.
        b[BS_DRVNUM_OFFSET] = 0x00
        b[BS_BOOTSIG_OFFSET] = 0x29
        u32(b, BS_VOLID_OFFSET, volumeId)
        label11.toByteArray(Charsets.US_ASCII).copyInto(b, BS_VOLLAB_OFFSET, 0, 11)
        Bpb.FS_TYPE_FAT12.toByteArray(Charsets.US_ASCII).copyInto(b, Bpb.FS_TYPE_OFFSET)

        // Boot signature.
        b[0x1FE] = 0x55
        b[0x1FF] = 0xAA.toByte()

        device.write(0L, ByteBuffer.wrap(b))
    }

    private fun writeFats(bytesPerSector: Int, geometry: Geometry, mediaDescriptor: Int) {
        val fatBytes = geometry.fatSz * bytesPerSector
        val fat = ByteArray(fatBytes)

        // Reserved entries: FAT[0] = 0xF00 | media (low byte mirrors the
        // BPB media descriptor per the FAT spec); FAT[1] = 0xFFF (EOC). Encoded
        // little-endian across 3 bytes; remaining clusters left FREE (0x000).
        fat[0] = (mediaDescriptor and 0xFF).toByte()
        fat[1] = 0xFF.toByte()
        fat[2] = 0xFF.toByte()

        // Both FAT copies written byte-for-byte identical.
        val fat0Offset = RSVD_SEC_CNT.toLong() * bytesPerSector
        for (fatIdx in 0 until NUM_FATS) {
            val base = fat0Offset + fatIdx.toLong() * fatBytes
            device.write(base, ByteBuffer.wrap(fat.copyOf()))
        }
    }

    private fun writeRootDir(bytesPerSector: Int, geometry: Geometry, label11: String) {
        val rootDirOffset =
            (RSVD_SEC_CNT + NUM_FATS * geometry.fatSz).toLong() * bytesPerSector
        val rootDirBytes = geometry.rootDirSectors * bytesPerSector
        val root = ByteArray(rootDirBytes)

        // Volume label also lives in a root-dir VOLUME_ID entry (attr 0x08)
        // carrying the same 11-byte label. The rest of the root dir is zeroed
        // (no files).
        label11.toByteArray(Charsets.US_ASCII).copyInto(root, 0, 0, 11)
        root[0x0B] = ATTR_VOLUME_ID.toByte()

        device.write(rootDirOffset, ByteBuffer.wrap(root))
    }

    private fun Int.ceilDiv(d: Int): Int = (this + d - 1) / d

    private fun u16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun u32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    companion object {
        /** Standard format geometry constants (match mkfs.fat for fixed-disk geometry). */
        const val RSVD_SEC_CNT: Int = 1
        const val NUM_FATS: Int = 2
        const val ROOT_ENT_CNT: Int = 224
        const val DIR_ENTRY_SIZE: Int = 32

        /** Media descriptor lives at BPB offset 0x15 (also mirrored in FAT[0]). */
        const val MEDIA_OFFSET: Int = 0x15

        /** Extended boot record offsets (BS_*). */
        const val BS_DRVNUM_OFFSET: Int = 0x24
        const val BS_BOOTSIG_OFFSET: Int = 0x26   // = 38 decimal; value 0x29
        const val BS_VOLID_OFFSET: Int = 0x27     // = 39 decimal
        const val BS_VOLLAB_OFFSET: Int = 0x2B    // = 43 decimal

        /** Directory entry attribute for a volume-label entry. */
        const val ATTR_VOLUME_ID: Int = 0x08
    }
}
