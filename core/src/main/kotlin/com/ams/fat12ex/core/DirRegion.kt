package com.ams.fat12ex.core

/**
 * FAT12 directory-region operations on a flat directory byte buffer.
 *
 * Generalized from the source `RootDir` (firmware-flasher app) to operate on ANY
 * directory `ByteArray` — the fixed-size root-directory region OR a concatenated
 * subdirectory cluster chain. Caller reads the region into a byte array, mutates via
 * these functions, then writes the array back. Pure / stateless — same shape as
 * [FatTable].
 *
 * Responsibilities (directory region):
 *   - [walk] groups LFN preamble entries with their trailing short entry; reads any
 *     flat directory buffer (root region or subdir cluster chain).
 *   - [encodeLfnPreamble] + [lfnChecksum] — production LFN write, MOVED VERBATIM from
 *     the (already-correct) `Fat12ImageBuilder` test fixture. Reverse-ordinal physical
 *     layout with `nEntries or 0x40` on the first physical slot and an identical
 *     checksum on every slot. NOT re-derived — silent-corruption-prone.
 *   - [writeShortEntryGeneric] writes a short 8.3 entry; applies the 0xE5 → 0x05
 *     first-byte substitution so a legitimately-0xE5-named entry is not read
 *     back as a deleted slot. [walk] decodes a leading 0x05 back to 0xE5.
 *   - [writeDotDotEntries] writes the `.` (= own cluster) and `..` (= parent cluster,
 *     or 0x0000 when the parent is root) entries.
 *   - [allocateEntry], [findEntry], [markDeleted] — directory-mutation substrate the
 *     Wave 3 mkdir/rename/recursive-delete build on.
 *   - [markDeletedAndFreeChain] marks all preamble + short slots 0xE5 and frees the
 *     FAT chain (ported from the source `delete`).
 */
object DirRegion {

    const val ENTRY_SIZE: Int = 32

    const val ATTR_LFN: Int = 0x0F
    const val ATTR_ARCHIVE: Int = 0x20
    const val ATTR_DIRECTORY: Int = 0x10

    val DELETED_SENTINEL: Byte = 0xE5.toByte()
    const val END_OF_DIR_SENTINEL: Byte = 0x00

    /**
     * `..`-of-root marker: when a subdirectory's parent IS the root directory, the
     * `..` entry's first-cluster field must be written as 0x0000, NOT the
     * sector offset of the root. [writeDotDotEntries] maps this marker to 0x0000.
     */
    const val ROOT_DIR_MARKER: Int = 0x0000

    /**
     * Valid non-zero DOS date/time defaults. A zero DOS date (0x0000 = invalid
     * 1980-00-00) is rejected by strict FAT readers; these keep a written entry valid
     * even when no clock is supplied. DOS date = ((year-1980)<<9)|(month<<5)|day.
     */
    const val DEFAULT_DOS_DATE: Int = 0x58C1
    const val DEFAULT_DOS_TIME: Int = 0x6000

    data class WalkedEntry(
        val shortEntryOffset: Int,
        val lfnEntryCount: Int,
        val shortName83: String,
        val attr: Int,
        val firstCluster: Int,
        val fileSize: Int,
    )

    /**
     * Walk a flat directory buffer, grouping each run of LFN preamble entries with the
     * short entry that follows it. Works on the root-dir region OR a concatenated
     * subdirectory cluster chain (the loop is bounded only by `dirBytes.size`).
     *
     * A leading 0x05 in a short entry's first byte is decoded back to 0xE5 in the
     * returned [WalkedEntry.shortName83].
     */
    fun walk(dirBytes: ByteArray): List<WalkedEntry> {
        val entries = mutableListOf<WalkedEntry>()
        var offset = 0
        var lfnRunStart = -1

        while (offset + ENTRY_SIZE <= dirBytes.size) {
            val firstByte = dirBytes[offset]
            if (firstByte == END_OF_DIR_SENTINEL) break

            if (firstByte == DELETED_SENTINEL) {
                lfnRunStart = -1
                offset += ENTRY_SIZE
                continue
            }

            val attr = dirBytes[offset + 0x0B].toInt() and 0xFF
            if (attr == ATTR_LFN) {
                if (lfnRunStart < 0) lfnRunStart = offset
                offset += ENTRY_SIZE
                continue
            }

            val shortName = decodeShortName(dirBytes, offset)
            val firstCluster = u16(dirBytes, offset + 0x1A)
            val fileSize = u32(dirBytes, offset + 0x1C)
            val lfnCount = if (lfnRunStart >= 0) (offset - lfnRunStart) / ENTRY_SIZE else 0

            entries.add(
                WalkedEntry(
                    shortEntryOffset = offset,
                    lfnEntryCount = lfnCount,
                    shortName83 = shortName,
                    attr = attr,
                    firstCluster = firstCluster,
                    fileSize = fileSize,
                )
            )
            lfnRunStart = -1
            offset += ENTRY_SIZE
        }
        return entries
    }

    /**
     * Decode the 11-byte 8.3 name at [offset], translating a leading 0x05 back to 0xE5
     * (the inverse of the [writeShortEntryGeneric] 0xE5 → 0x05 substitution).
     *
     * Decoded with ISO-8859-1 (Latin-1), NOT US-ASCII: Latin-1 is a faithful 1:1
     * byte↔char mapping over 0x00–0xFF, so a restored 0xE5 name byte appears as char
     * 0xE5. US-ASCII would corrupt any byte ≥ 0x80 (including the decoded 0xE5) to the
     * replacement char U+FFFD, defeating the 0xE5 round-trip the engine requires.
     */
    private fun decodeShortName(dirBytes: ByteArray, offset: Int): String {
        val raw = ByteArray(11)
        dirBytes.copyInto(raw, 0, offset, offset + 11)
        if (raw[0] == 0x05.toByte()) raw[0] = 0xE5.toByte()
        return String(raw, Charsets.ISO_8859_1)
    }

    /**
     * Mark every preamble + short slot of [entry] as 0xE5 and free its FAT chain.
     * Ported from the source `RootDir.delete`. Returns the number of clusters freed.
     */
    fun markDeletedAndFreeChain(dirBytes: ByteArray, fat: ByteArray, entry: WalkedEntry): Int {
        val preambleStart = entry.shortEntryOffset - entry.lfnEntryCount * ENTRY_SIZE
        for (i in 0 until entry.lfnEntryCount) {
            dirBytes[preambleStart + i * ENTRY_SIZE] = DELETED_SENTINEL
        }
        dirBytes[entry.shortEntryOffset] = DELETED_SENTINEL
        return if (entry.firstCluster >= 2) FatTable.freeChain(fat, entry.firstCluster) else 0
    }

    /**
     * Convert an arbitrary filename to an 8.3 short directory name (11 bytes, space-
     * padded, UPPERCASE on disk) plus the VFAT case byte (NT-reserved, offset 0x0C):
     * bit 0x08 = base all-lowercase, bit 0x10 = extension all-lowercase.
     *
     * A clean lowercase 8.3 name (e.g. "bf.bin") becomes "BF      BIN" + case 0x18.
     * Names that do not fit 8.3 are mangled to "NAME~1.EXT" with case 0x00.
     */
    fun shortName83(fileName: String): Pair<String, Int> {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = if (fileName.contains('.')) fileName.substringAfterLast('.') else ""
        val baseClean = base.filter { it.isLetterOrDigit() }
        val extClean = ext.filter { it.isLetterOrDigit() }
        val fits = baseClean.length in 1..8 && extClean.length <= 3 &&
            baseClean.length == base.length && extClean.length == ext.length
        val base83: String
        var caseByte = 0
        if (fits) {
            base83 = baseClean.uppercase().padEnd(8, ' ')
            if (base.any { it.isLowerCase() } && base.none { it.isUpperCase() }) caseByte = caseByte or 0x08
            if (ext.any { it.isLowerCase() } && ext.none { it.isUpperCase() }) caseByte = caseByte or 0x10
        } else {
            val stem = baseClean.uppercase().take(6).ifEmpty { "FW" }
            base83 = "$stem~1".padEnd(8, ' ')
        }
        val ext83 = extClean.uppercase().take(3).padEnd(3, ' ')
        return Pair(base83 + ext83, caseByte)
    }

    /**
     * Current local time packed as (dosDate, dosTime) for a directory entry.
     * Ported from `FatFlasher.currentDosDateTime`. A zero DOS date is invalid to
     * strict readers, so callers default to this rather than 0.
     */
    fun currentDosDateTime(): Pair<Int, Int> {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR).coerceAtLeast(1980)
        val date = ((year - 1980) shl 9) or
            ((cal.get(java.util.Calendar.MONTH) + 1) shl 5) or
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        val time = (cal.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (cal.get(java.util.Calendar.MINUTE) shl 5) or
            (cal.get(java.util.Calendar.SECOND) / 2)
        return Pair(date, time)
    }

    /**
     * Write a 32-byte short 8.3 directory entry at [offset].
     *
     * Generalized from the source `RootDir.writeShortEntry` with an explicit [attr]
     * parameter (so directory entries can carry ATTR_DIRECTORY, files ATTR_ARCHIVE).
     * Applies the 0xE5 substitution: if the first name byte is 0xE5 it is stored as
     * 0x05 so the entry is not mis-read as a deleted slot ([walk] decodes it back).
     */
    fun writeShortEntryGeneric(
        dirBytes: ByteArray,
        offset: Int,
        name83: String,
        attr: Int,
        caseByte: Int,
        firstCluster: Int,
        fileSize: Int,
        dosDate: Int,
        dosTime: Int,
    ) {
        for (i in 0 until ENTRY_SIZE) dirBytes[offset + i] = 0
        name83.toByteArray(Charsets.US_ASCII).copyInto(dirBytes, offset, 0, 11)
        // A leading 0xE5 name byte must be written as 0x05 on disk.
        if (name83[0].code == 0xE5) dirBytes[offset] = 0x05
        dirBytes[offset + 0x0B] = attr.toByte()
        dirBytes[offset + 0x0C] = caseByte.toByte()       // VFAT NT case byte
        u16(dirBytes, offset + 0x0E, dosTime)             // crtTime
        u16(dirBytes, offset + 0x10, dosDate)             // crtDate
        u16(dirBytes, offset + 0x12, dosDate)             // lstAccDate
        u16(dirBytes, offset + 0x16, dosTime)             // wrtTime
        u16(dirBytes, offset + 0x18, dosDate)             // wrtDate
        u16(dirBytes, offset + 0x1A, firstCluster)
        u32(dirBytes, offset + 0x1C, fileSize)
    }

    /**
     * Find the first run of [count] consecutive free/deleted slots (first byte 0x00 or
     * 0xE5) and return its start offset, or -1 when none fits (caller maps -1 to
     * [Fat12Result.DiskFull]). Used to allocate room for an LFN preamble + short entry.
     */
    fun allocateEntry(dirBytes: ByteArray, count: Int): Int {
        require(count >= 1) { "count must be positive: $count" }
        var offset = 0
        while (offset + count * ENTRY_SIZE <= dirBytes.size) {
            var run = 0
            while (run < count) {
                val firstByte = dirBytes[offset + run * ENTRY_SIZE]
                if (firstByte != END_OF_DIR_SENTINEL && firstByte != DELETED_SENTINEL) break
                run++
            }
            if (run == count) return offset
            // Skip past the occupied slot that broke the run.
            offset += (run + 1) * ENTRY_SIZE
        }
        return -1
    }

    /**
     * Write the `.` and `..` reserved entries at the start of a freshly-allocated
     * subdirectory cluster:
     *   - `.`  → own cluster ([ownCluster]) at offset 0
     *   - `..` → parent cluster at offset 32, OR 0x0000 when [parentCluster] is
     *            [ROOT_DIR_MARKER] (the parent is the root directory).
     */
    fun writeDotDotEntries(dirBytes: ByteArray, ownCluster: Int, parentCluster: Int) {
        val (dosDate, dosTime) = currentDosDateTime()
        writeShortEntryGeneric(
            dirBytes = dirBytes,
            offset = 0,
            name83 = ".          ",          // "." + 10 spaces (11 bytes)
            attr = ATTR_DIRECTORY,
            caseByte = 0,
            firstCluster = ownCluster,
            fileSize = 0,
            dosDate = dosDate,
            dosTime = dosTime,
        )
        val dotDotCluster = if (parentCluster == ROOT_DIR_MARKER) 0x0000 else parentCluster
        writeShortEntryGeneric(
            dirBytes = dirBytes,
            offset = ENTRY_SIZE,
            name83 = "..         ",          // ".." + 9 spaces (11 bytes)
            attr = ATTR_DIRECTORY,
            caseByte = 0,
            firstCluster = dotDotCluster,
            fileSize = 0,
            dosDate = dosDate,
            dosTime = dosTime,
        )
    }

    /**
     * Locate the entry whose LFN-decoded name OR raw 8.3 short name equals [name],
     * else null. (Wave 3's facade resolves the LFN name before calling; until then the
     * 8.3 short-name match is the available path. The comparison also accepts the
     * trimmed 8.3 name so "FOO" matches the on-disk "FOO        ".)
     */
    fun findEntry(dirBytes: ByteArray, name: String): WalkedEntry? {
        return walk(dirBytes).firstOrNull {
            it.shortName83 == name || it.shortName83.trim() == name
        }
    }

    /**
     * Mark the [lfnCount] preamble slots and the short slot at [offset] as 0xE5.
     * Does NOT touch the FAT — the caller owns the FAT free (so an undo-log can order
     * directory-entry deletion relative to FAT changes in Wave 3).
     */
    fun markDeleted(dirBytes: ByteArray, offset: Int, lfnCount: Int) {
        val preambleStart = offset - lfnCount * ENTRY_SIZE
        for (i in 0 until lfnCount) {
            dirBytes[preambleStart + i * ENTRY_SIZE] = DELETED_SENTINEL
        }
        dirBytes[offset] = DELETED_SENTINEL
    }

    /**
     * Encode the LFN preamble entries for [displayName] bound to short name [short83].
     *
     * MOVED VERBATIM from the (already-correct) `Fat12ImageBuilder.encodeLfnPreamble`
     * — DO NOT re-derive. LFN entries are written PHYSICALLY in reverse order
     * (last-sequence-first): the first element of the returned list is the highest
     * ordinal and carries `seq or 0x40`; the last element is ordinal 1. Every element
     * carries the same [lfnChecksum] of [short83] at offset 0x0D and ATTR_LFN (0x0F)
     * at 0x0B.
     */
    fun encodeLfnPreamble(displayName: String, short83: String): List<ByteArray> {
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

    /**
     * LFN checksum of the canonical 11-byte short name. MOVED VERBATIM from
     * `Fat12ImageBuilder.lfnChecksum`. Must be identical across every LFN slot for the
     * same file or the entire LFN is silently dropped.
     */
    fun lfnChecksum(name83: String): Byte {
        var sum = 0
        for (c in name83.toByteArray(Charsets.US_ASCII)) {
            sum = ((sum and 1) shl 7) + (sum ushr 1) + (c.toInt() and 0xFF)
            sum = sum and 0xFF
        }
        return sum.toByte()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

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
