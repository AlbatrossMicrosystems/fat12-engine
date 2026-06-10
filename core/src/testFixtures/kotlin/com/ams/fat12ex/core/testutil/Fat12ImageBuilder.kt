package com.ams.fat12ex.core.testutil

import com.ams.fat12ex.core.Bpb

/**
 * Programmatic FAT12 image generator for unit tests.
 *
 * Lays out a synthetic FAT12 volume (BPB sector + 2 FATs + root directory +
 * data clusters) into an [InMemoryBlockDevice]'s backing array.
 *
 * Defaults match the Radix 2 HD partition layout (Wave-0 hardware test
 * results §2): 512 B/sector, 8 sec/cluster (= 4 KB clusters), 1 reserved
 * sector, 2 FATs of 3 sectors each, 64 root entries (4 sectors), ~954
 * data clusters.
 */
class Fat12ImageBuilder(
    val bytsPerSec: Int = 512,
    val secPerClus: Int = 8,
    val rsvdSecCnt: Int = 1,
    val numFATs: Int = 2,
    val rootEntCnt: Int = 64,
    val fatSz: Int = 3,
    val clusterCount: Int = 954,
    /** Filesystem-type ASCII at BPB offset 0x36. Override for negative tests (e.g. "FAT32   "). */
    val fsType: String = Bpb.FS_TYPE_FAT12,
) {

    data class ShortEntry(
        val name83: String,
        val attr: Int,
        val firstCluster: Int,
        val fileSize: Int,
        val data: ByteArray,
    )

    data class LfnFile(
        val displayName: String,
        val short: ShortEntry,
    )

    private val plainShortEntries = mutableListOf<ShortEntry>()
    private val lfnFiles = mutableListOf<LfnFile>()

    fun withReservedShortEntry(
        name83: String,
        attr: Int = 0x20,
        clusters: List<Int> = emptyList(),
        bytes: ByteArray = ByteArray(0),
    ): Fat12ImageBuilder {
        require(name83.length == 11) { "name83 must be 11 chars, got '${name83}' (len=${name83.length})" }
        plainShortEntries.add(
            ShortEntry(
                name83 = name83,
                attr = attr,
                firstCluster = clusters.firstOrNull() ?: 0,
                fileSize = bytes.size,
                data = bytes,
            )
        )
        return this
    }

    fun withFirmwareLfn(
        displayName: String,
        clusters: List<Int>,
        bytes: ByteArray,
    ): Fat12ImageBuilder {
        val short83 = generateShort83(displayName)
        lfnFiles.add(
            LfnFile(
                displayName = displayName,
                short = ShortEntry(
                    name83 = short83,
                    attr = 0x20,
                    firstCluster = clusters.firstOrNull() ?: 0,
                    fileSize = bytes.size,
                    data = bytes,
                ),
            )
        )
        return this
    }

    fun build(): InMemoryBlockDevice {
        val rootSectors = (rootEntCnt * 32 + bytsPerSec - 1) / bytsPerSec
        val totSec = rsvdSecCnt + numFATs * fatSz + rootSectors + clusterCount * secPerClus
        val device = InMemoryBlockDevice(
            blockSize = bytsPerSec,
            blocks = totSec.toLong(),
        )
        writeBpb(device, totSec)
        writeFats(device)
        writeRootDir(device)
        writeData(device)
        return device
    }

    private fun writeBpb(device: InMemoryBlockDevice, totSec: Int) {
        val b = device.bytes
        b[0] = 0xEB.toByte(); b[1] = 0x3C; b[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(b, 3)
        u16(b, Bpb.BYTSPERSEC_OFFSET, bytsPerSec)
        b[Bpb.SECPERCLUS_OFFSET] = secPerClus.toByte()
        u16(b, Bpb.RSVDSECCNT_OFFSET, rsvdSecCnt)
        b[Bpb.NUMFATS_OFFSET] = numFATs.toByte()
        u16(b, Bpb.ROOTENTCNT_OFFSET, rootEntCnt)
        u16(b, Bpb.TOTSEC16_OFFSET, if (totSec < 0x10000) totSec else 0)
        b[0x15] = 0xF8.toByte()
        u16(b, Bpb.FATSZ16_OFFSET, fatSz)
        u32(b, Bpb.TOTSEC32_OFFSET, if (totSec >= 0x10000) totSec else 0)
        val padded = fsType.padEnd(Bpb.FS_TYPE_LEN, ' ').take(Bpb.FS_TYPE_LEN)
        padded.toByteArray(Charsets.US_ASCII).copyInto(b, Bpb.FS_TYPE_OFFSET)
        b[0x1FE] = 0x55; b[0x1FF] = 0xAA.toByte()
    }

    private fun writeFats(device: InMemoryBlockDevice) {
        val b = device.bytes
        val fatStart = rsvdSecCnt * bytsPerSec
        val fatLen = fatSz * bytsPerSec
        for (fatIdx in 0 until numFATs) {
            val base = fatStart + fatIdx * fatLen
            // f[0] = 0xFF8, f[1] = 0xFFF
            b[base + 0] = 0xF8.toByte()
            b[base + 1] = 0xFF.toByte()
            b[base + 2] = 0xFF.toByte()
        }
        val allEntries = plainShortEntries.map { it.firstCluster to listOfClusters(it) } +
                lfnFiles.map { it.short.firstCluster to listOfClusters(it.short) }
        for ((_, chain) in allEntries) {
            if (chain.isEmpty()) continue
            for (i in chain.indices) {
                val n = chain[i]
                val next = if (i == chain.lastIndex) 0xFFF else chain[i + 1]
                writeFat12Entry(b, fatStart, n, next)
                if (numFATs > 1) writeFat12Entry(b, fatStart + fatLen, n, next)
            }
        }
    }

    private fun listOfClusters(e: ShortEntry): List<Int> {
        if (e.firstCluster == 0 || e.data.isEmpty()) return emptyList()
        val clusterBytes = bytsPerSec * secPerClus
        val nClusters = (e.data.size + clusterBytes - 1) / clusterBytes
        return (0 until nClusters).map { e.firstCluster + it }
    }

    private fun writeFat12Entry(b: ByteArray, fatBase: Int, n: Int, value: Int) {
        val off = fatBase + n + (n shr 1)
        if (n and 1 == 0) {
            b[off] = (value and 0xFF).toByte()
            b[off + 1] = ((b[off + 1].toInt() and 0xF0) or ((value shr 8) and 0x0F)).toByte()
        } else {
            b[off] = ((b[off].toInt() and 0x0F) or ((value shl 4) and 0xF0)).toByte()
            b[off + 1] = ((value shr 4) and 0xFF).toByte()
        }
    }

    private fun writeRootDir(device: InMemoryBlockDevice) {
        val b = device.bytes
        val rootStart = (rsvdSecCnt + numFATs * fatSz) * bytsPerSec
        var entryOff = rootStart
        for (e in plainShortEntries) {
            writeShortEntry(b, entryOff, e)
            entryOff += 32
        }
        for (lf in lfnFiles) {
            val lfnEntries = encodeLfnPreamble(lf.displayName, lf.short.name83)
            for (lfnBytes in lfnEntries) {
                lfnBytes.copyInto(b, entryOff)
                entryOff += 32
            }
            writeShortEntry(b, entryOff, lf.short)
            entryOff += 32
        }
    }

    private fun writeShortEntry(b: ByteArray, off: Int, e: ShortEntry) {
        e.name83.toByteArray(Charsets.US_ASCII).copyInto(b, off, 0, 11)
        b[off + 0x0B] = e.attr.toByte()
        u16(b, off + 0x1A, e.firstCluster)
        u32(b, off + 0x1C, e.fileSize)
    }

    private fun writeData(device: InMemoryBlockDevice) {
        val b = device.bytes
        val rootSectors = (rootEntCnt * 32 + bytsPerSec - 1) / bytsPerSec
        val dataStart = (rsvdSecCnt + numFATs * fatSz + rootSectors) * bytsPerSec
        val clusterBytes = bytsPerSec * secPerClus
        for (e in plainShortEntries + lfnFiles.map { it.short }) {
            if (e.firstCluster == 0 || e.data.isEmpty()) continue
            val off = dataStart + (e.firstCluster - 2) * clusterBytes
            e.data.copyInto(b, off)
        }
    }

    private fun encodeLfnPreamble(displayName: String, short83: String): List<ByteArray> {
        val checksum = lfnChecksum(short83)
        val charsPerEntry = 13
        val nEntries = (displayName.length + charsPerEntry - 1) / charsPerEntry
        // LFN entries are written PHYSICALLY in reverse order (last-sequence-first).
        return (1..nEntries).reversed().map { seq ->
            val entry = ByteArray(32)
            entry[0] = if (seq == nEntries) (seq or 0x40).toByte() else seq.toByte()
            entry[0x0B] = 0x0F
            entry[0x0D] = checksum
            val baseChar = (seq - 1) * charsPerEntry
            for (i in 0 until charsPerEntry) {
                val charIdx = baseChar + i
                val c = when {
                    charIdx < displayName.length -> displayName[charIdx].code
                    charIdx == displayName.length -> 0
                    else -> 0xFFFF
                }
                val dst = when {
                    i < 5 -> 0x01 + i * 2
                    i < 11 -> 0x0E + (i - 5) * 2
                    else -> 0x1C + (i - 11) * 2
                }
                entry[dst] = (c and 0xFF).toByte()
                entry[dst + 1] = ((c shr 8) and 0xFF).toByte()
            }
            entry
        }
    }

    private fun lfnChecksum(name83: String): Byte {
        var sum = 0
        for (c in name83.toByteArray(Charsets.US_ASCII)) {
            sum = ((sum and 1) shl 7) + (sum ushr 1) + (c.toInt() and 0xFF)
            sum = sum and 0xFF
        }
        return sum.toByte()
    }

    private fun generateShort83(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot >= 0) displayName.substring(0, dot) else displayName
        val ext = if (dot >= 0) displayName.substring(dot + 1) else ""
        val baseAscii = base.uppercase().filter { it.isLetterOrDigit() }.take(6).padEnd(6, ' ')
        val nameField = (baseAscii + "~1").padEnd(8, ' ')
        val extField = ext.uppercase().filter { it.isLetterOrDigit() }.take(3).padEnd(3, ' ')
        return nameField + extField
    }

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
}
