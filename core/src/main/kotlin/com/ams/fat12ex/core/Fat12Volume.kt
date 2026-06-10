package com.ams.fat12ex.core

import java.io.Closeable
import java.io.IOException

/**
 * The facade — the single high-level surface the `:app` UI (and the USB-OTG
 * backend) consume to work with a FAT12 volume. Wraps a [BlockDevice]
 * and aggregates open/close, volume-info, directory listing, file read, the
 * streaming `writeFile`, and `format` delegation.
 *
 * The crown jewel is [writeFile]: it streams a [Sequence] of chunks into
 * freshly-allocated data clusters, verifies every cluster by reading it back,
 * and — owning an [UndoLog] for the whole operation — rolls the entire file
 * write back to a byte-for-byte identical pre-operation state on ANY failure
 * (write IOException, verify mismatch, or disk-full). This is the
 * atomic-verify-after-write + rollback contract, the project's north star.
 *
 * Write ordering is ported verbatim from the source `FatFlasher.flashFile`:
 * data clusters -> FAT[0] -> FAT[1] -> directory entry LAST. A
 * true power-loss worst case therefore leaks a cluster (fsck-recoverable),
 * never a cross-linked directory entry pointing at unallocated/shared space.
 *
 * Carries no Android or USB-library imports — pure JVM.
 */
class Fat12Volume(private val device: BlockDevice) : Closeable {

    private lateinit var bpb: Bpb

    // ----- lifecycle ---------------------------------------------------------

    /**
     * Parse the BPB (sector 0). Propagates [NotFat12Exception] for a non-FAT12
     * volume per the thrown half (cluster-count gate inside [Bpb.parse]).
     * Returns [Fat12Result.Ok] on a valid FAT12 volume.
     */
    fun open(): Fat12Result<Unit> {
        bpb = Bpb.parse(device)
        return Fat12Result.Ok(Unit)
    }

    /** No-op for the in-memory/USB backends (the device owns its own lifecycle). */
    override fun close() { /* nothing to release at the facade layer */ }

    // ----- volume info -------------------------------------------------------

    /** Cluster size in bytes (bytsPerSec * secPerClus). */
    fun clusterSize(): Int = bpb.clusterSize

    /** Total addressable data capacity = data clusters * cluster size. */
    fun totalBytes(): Long = bpb.clusterCount.toLong() * bpb.clusterSize

    /** Free capacity = free data clusters (from FAT[0]) * cluster size. */
    fun freeBytes(): Long {
        val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        return FatTable.countFreeClusters(fat, bpb.clusterCount).toLong() * bpb.clusterSize
    }

    /**
     * The volume label. Prefers the root-directory `ATTR_VOLUME_ID` entry — the
     * authoritative label external tools (and Windows) display — falling back to the
     * BPB `BS_VolLab` copy at offset 0x2B, then "". Some formatters set only the root
     * volume-ID entry and leave `BS_VolLab` as "NO NAME" (e.g. the BrainFPV Radix
     * firmware): reading only `BS_VolLab` would mislabel such a volume as "NO NAME".
     * The 11 raw label bytes are read directly (NOT via the 8.3 name decoder, which
     * would wrongly splice a '.' into the label) and trimmed of trailing spaces.
     */
    fun volumeLabel(): String {
        val rootBytes = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        val volId = DirRegion.walk(rootBytes)
            .firstOrNull { (it.attr and ATTR_VOLUME_ID) != 0 }
        if (volId != null) {
            val fromRoot = String(rootBytes, volId.shortEntryOffset, VOL_LAB_LEN, Charsets.ISO_8859_1).trimEnd()
            if (fromRoot.isNotEmpty()) return fromRoot
        }
        val sector = readBytes(device, 0L, bpb.bytsPerSec)
        return String(sector, BS_VOLLAB_OFFSET, VOL_LAB_LEN, Charsets.US_ASCII).trimEnd()
    }

    // ----- directory listing -------------------------------------------------

    /**
     * List the entries in the directory at [path] (root = "" or "/"). Returns
     * each non-volume-label entry as a [Fat12Entry], resolving an LFN display
     * name when an LFN preamble precedes the short entry, else the decoded 8.3
     * name. A path that does not resolve to an existing directory returns
     * [Fat12Result.NotFound].
     */
    fun list(path: String): Fat12Result<List<Fat12Entry>> {
        val dirBytes = readDirBytes(path) ?: return Fat12Result.NotFound(path)
        val entries = DirRegion.walk(dirBytes)
            .filter { (it.attr and ATTR_VOLUME_ID) == 0 }  // skip the volume-label entry
            .map { toFat12Entry(dirBytes, it) }
        return Fat12Result.Ok(entries)
    }

    /**
     * Read the file at [path] by walking its cluster chain and concatenating the
     * cluster bytes, truncated to the directory entry's file size. A missing
     * file (or a path whose parent directory does not exist) returns
     * [Fat12Result.NotFound].
     */
    fun readFile(path: String): Fat12Result<ByteArray> {
        val (parentDirBytes, leaf) = resolveParent(path)
            ?: return Fat12Result.NotFound(path)
        val entry = findEntryByName(parentDirBytes, leaf)
            ?: return Fat12Result.NotFound(path)

        if (entry.firstCluster < 2 || entry.fileSize == 0) {
            return Fat12Result.Ok(ByteArray(0))
        }
        val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        val chain = FatTable.walkChain(fat, entry.firstCluster)
        val out = ByteArray(chain.size * bpb.clusterSize)
        for ((i, cluster) in chain.withIndex()) {
            val clusterBytes = readBytes(device, bpb.byteOffsetOfCluster(cluster), bpb.clusterSize)
            clusterBytes.copyInto(out, i * bpb.clusterSize)
        }
        // Truncate to the recorded file size (file size as Int — FAT12 volumes are small).
        val size = entry.fileSize.coerceAtMost(out.size)
        return Fat12Result.Ok(out.copyOf(size))
    }

    // ----- streaming write (INT-02) ------------------------------------------

    /**
     * Stream [source] (a [Sequence] of byte chunks) into the file at [path] under
     * the atomic verify-after-write + rollback contract:
     *
     *  1. Buffer the chunks into cluster-sized payloads.
     *  2. Allocate the cluster chain in the in-memory FAT.
     *  3. Write each data cluster, then read it back and byte-compare (verify);
     *     a mismatch throws [FatVerifyFailedException]. Invoke [onProgress] after
     *     each verified cluster.
     *  4. Write FAT[0], then FAT[1] (dual-FAT sync).
     *  5. Write the directory entry LAST (commit point).
     *
     * BEFORE every device write, the sector(s) it touches are captured in an
     * [UndoLog]. On ANY failure the whole operation is rolled back to the exact
     * pre-op bytes and the failure is surfaced (a [Fat12Result] for recoverable
     * outcomes such as disk-full, or the thrown verify/write exception).
     * On success the journal is committed and [Fat12Result.Ok] is returned.
     *
     * v1 writes into the directory addressed by [path]'s parent (root supported;
     * nested parent directories are resolved through their cluster chain).
     */
    fun writeFile(
        path: String,
        source: Sequence<ByteArray>,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Fat12Result<Unit> {
        val parentPath = parentOf(path)
        val leaf = leafOf(path)
        if (leaf.isEmpty()) return Fat12Result.InvalidName(path, "empty file name")

        // Locate the parent directory region on disk (root region or subdir chain).
        val parentLoc = locateDir(parentPath) ?: return Fat12Result.NotFound(parentPath)

        // Materialize the streamed chunks into one contiguous payload, then split
        // into cluster-sized writes. Buffering per-chunk keeps the API streaming-
        // friendly while still letting us compute the exact cluster need.
        val payload = concatChunks(source)
        val totalBytes = payload.size.toLong()

        val undoLog = UndoLog(device)
        try {
            // ---- read working copies of FAT + parent directory --------------
            val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
            val dirBytes = parentLoc.bytes.copyOf()

            // Name conflict guard (recoverable outcome).
            val (short83, caseByte) = DirRegion.shortName83(leaf)
            if (findEntryByName(dirBytes, leaf) != null ||
                findEntryByName(dirBytes, short83) != null
            ) {
                return Fat12Result.NameConflict(leaf)
            }

            // ---- compute cluster need + free space --------------------------
            val needClusters = if (payload.isEmpty()) 0
            else (payload.size + bpb.clusterSize - 1) / bpb.clusterSize
            val freeClusters = FatTable.countFreeClusters(fat, bpb.clusterCount)
            if (needClusters > freeClusters) return Fat12Result.DiskFull

            // Reserve directory-entry slots (LFN preamble + short entry).
            val needsLfn = decideNeedsLfn(leaf, short83)
            val lfnEntries = if (needsLfn) DirRegion.encodeLfnPreamble(leaf, short83) else emptyList()
            val slotsNeeded = lfnEntries.size + 1
            val dirOffset = DirRegion.allocateEntry(dirBytes, slotsNeeded)
            if (dirOffset < 0) return Fat12Result.DiskFull

            // ---- allocate the cluster chain in the in-memory FAT ------------
            val chain = if (needClusters > 0) {
                FatTable.allocateChain(fat, needClusters, bpb.clusterCount)
            } else {
                emptyList()
            }

            // ---- capture every data cluster's TRUE pre-image up front -------
            // HR-01 (01-REVIEW.md): a cluster the allocator hands back may have been
            // FREED by a prior delete (FAT entry FREE) yet still physically hold the
            // deleted file's residual bytes — delete()/freeChain never zero the data.
            // So the genuine pre-op content is NOT necessarily zero. Capture each
            // data cluster's real bytes into the UndoLog (the exact same pre-image
            // machinery FAT + dir use) so rollback restores the true pre-image
            // byte-for-byte, not a zero-fill. The capture-read happens BEFORE the
            // first write to the cluster; the verify-read happens AFTER, so the two
            // never contend on the device (the previous zero-on-rollback shortcut is
            // dropped — see the SUMMARY for how the one-shot fault test was retuned).
            for (cluster in chain) {
                captureRegion(undoLog, bpb.byteOffsetOfCluster(cluster), bpb.clusterSize)
            }

            // ---- write data clusters with per-cluster verify-read -----------
            var bytesWritten = 0L
            for ((i, cluster) in chain.withIndex()) {
                val byteOff = bpb.byteOffsetOfCluster(cluster)
                val src = ByteArray(bpb.clusterSize)
                val srcStart = i * bpb.clusterSize
                val srcEnd = minOf(srcStart + bpb.clusterSize, payload.size)
                payload.copyInto(src, 0, srcStart, srcEnd)

                writeBytes(device, byteOff, src)

                // Verify-read pass (RDX-FAT-05): read back and byte-compare.
                val readBack = readBytes(device, byteOff, bpb.clusterSize)
                for (j in 0 until bpb.clusterSize) {
                    if (readBack[j] != src[j]) {
                        throw FatVerifyFailedException(
                            sectorOffset = byteOff + j,
                            expected = src[j],
                            actual = readBack[j],
                        )
                    }
                }

                bytesWritten = minOf(srcEnd.toLong(), totalBytes)
                onProgress(bytesWritten, totalBytes)
            }
            // For a zero-byte file there is no data chunk; still report completion.
            if (chain.isEmpty()) onProgress(0L, totalBytes)

            // ---- write FAT[0] then FAT[1] (dual-FAT sync, FAT before dir) ---
            captureRegion(undoLog, bpb.fat0Offset, bpb.fatBytes)
            writeBytes(device, bpb.fat0Offset, fat)
            if (bpb.numFATs >= 2) {
                captureRegion(undoLog, bpb.fat1Offset, bpb.fatBytes)
                writeBytes(device, bpb.fat1Offset, fat)
            }

            // ---- write the directory entry LAST (commit point) --------------
            val (dosDate, dosTime) = DirRegion.currentDosDateTime()
            var slot = dirOffset
            for (lfn in lfnEntries) {
                lfn.copyInto(dirBytes, slot)
                slot += DirRegion.ENTRY_SIZE
            }
            DirRegion.writeShortEntryGeneric(
                dirBytes = dirBytes,
                offset = slot,
                name83 = short83,
                attr = DirRegion.ATTR_ARCHIVE,
                caseByte = caseByte,
                firstCluster = chain.firstOrNull() ?: 0,
                fileSize = payload.size,
                dosDate = dosDate,
                dosTime = dosTime,
            )
            captureRegion(undoLog, parentLoc.offset, parentLoc.bytes.size)
            writeBytes(device, parentLoc.offset, dirBytes)

            // ---- success ----------------------------------------------------
            undoLog.commit()
            return Fat12Result.Ok(Unit)
        } catch (e: FatVerifyFailedException) {
            undoLog.rollback()
            throw e
        } catch (e: IOException) {
            undoLog.rollback()
            throw FatWriteFailedException(e)
        } catch (e: Throwable) {
            // Any other failure mid-write: restore pre-op state before propagating.
            undoLog.rollback()
            throw e
        }
    }

    // ----- mkdir ('.'/'..' clusters, undo-log-wrapped) -----------------------

    /**
     * Create a subdirectory at [path] under the atomic verify-after-write +
     * rollback contract, extended to this COMPOUND op.
     *
     * Allocates ONE directory cluster, writes the reserved `.` and `..` entries into
     * it (`.`→the new cluster, `..`→the parent's first cluster, or 0x0000 when
     * the parent is the root directory), then links a new [DirRegion.ATTR_DIRECTORY]
     * entry into the parent directory. Write ordering is FAT-allocation-first
     * the new cluster's bytes -> FAT[0] -> FAT[1] -> the PARENT
     * directory entry LAST (the commit point). A worst-case interruption therefore
     * leaks the new cluster, never a parent entry pointing at uninitialized/shared
     * space.
     *
     * BEFORE every device write the touched sector(s) — including the new dir
     * cluster — are captured in an [UndoLog]; on ANY failure the UndoLog replays
     * every pre-image (the new cluster's TRUE prior bytes, FAT, and parent-dir
     * metadata), leaving the volume byte-for-byte identical to its pre-op state
     * (T-01-24 / HR-01: a reused cluster's residual bytes are restored, not zeroed).
     *
     * Errors: a name already present in the parent → [Fat12Result.NameConflict]; no
     * free cluster → [Fat12Result.DiskFull]; the parent path missing →
     * [Fat12Result.NotFound]; an empty leaf name → [Fat12Result.InvalidName].
     */
    fun mkdir(path: String): Fat12Result<Unit> {
        val parentPath = parentOf(path)
        val leaf = leafOf(path)
        if (leaf.isEmpty()) return Fat12Result.InvalidName(path, "empty directory name")

        val parentLoc = locateDir(parentPath) ?: return Fat12Result.NotFound(parentPath)

        val undoLog = UndoLog(device)
        try {
            val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
            val dirBytes = parentLoc.bytes.copyOf()

            // Name-conflict guard (recoverable outcome) — before any write.
            val (short83, caseByte) = DirRegion.shortName83(leaf)
            if (findEntryByName(dirBytes, leaf) != null ||
                findEntryByName(dirBytes, short83) != null
            ) {
                return Fat12Result.NameConflict(leaf)
            }

            // Reserve the parent directory slot(s) (LFN preamble + short entry).
            val needsLfn = decideNeedsLfn(leaf, short83)
            val lfnEntries = if (needsLfn) DirRegion.encodeLfnPreamble(leaf, short83) else emptyList()
            val slotsNeeded = lfnEntries.size + 1
            val dirOffset = DirRegion.allocateEntry(dirBytes, slotsNeeded)
            if (dirOffset < 0) return Fat12Result.DiskFull

            // Allocate ONE directory cluster in the in-memory FAT.
            val newCluster = try {
                FatTable.allocateChain(fat, 1, bpb.clusterCount).first()
            } catch (e: NoFreeSpaceException) {
                return Fat12Result.DiskFull
            }

            // Build the new cluster: '.' (own cluster) and '..' (parent cluster, or
            // ROOT_DIR_MARKER -> 0x0000 when the parent is the root directory).
            val clusterBuf = ByteArray(bpb.clusterSize)
            DirRegion.writeDotDotEntries(
                dirBytes = clusterBuf,
                ownCluster = newCluster,
                parentCluster = parentLoc.firstCluster,
            )

            // ---- write the new dir cluster (with verify-after-write) ----------
            // Capture the cluster's TRUE pre-image first (HR-01): a reused cluster
            // freed by a prior delete may still hold residual bytes, so rollback must
            // restore them exactly — not zero the cluster. Capture-read precedes the
            // write; the verify-read follows it, so the two never contend.
            val clusterOff = bpb.byteOffsetOfCluster(newCluster)
            captureRegion(undoLog, clusterOff, bpb.clusterSize)
            writeBytes(device, clusterOff, clusterBuf)
            verifyRegion(clusterOff, clusterBuf)

            // ---- FAT[0] then FAT[1] (dual-FAT sync, FAT before dir) -----------
            captureRegion(undoLog, bpb.fat0Offset, bpb.fatBytes)
            writeBytes(device, bpb.fat0Offset, fat)
            if (bpb.numFATs >= 2) {
                captureRegion(undoLog, bpb.fat1Offset, bpb.fatBytes)
                writeBytes(device, bpb.fat1Offset, fat)
            }

            // ---- write the PARENT directory entry LAST (commit point) ---------
            val (dosDate, dosTime) = DirRegion.currentDosDateTime()
            var slot = dirOffset
            for (lfn in lfnEntries) {
                lfn.copyInto(dirBytes, slot)
                slot += DirRegion.ENTRY_SIZE
            }
            DirRegion.writeShortEntryGeneric(
                dirBytes = dirBytes,
                offset = slot,
                name83 = short83,
                attr = DirRegion.ATTR_DIRECTORY,
                caseByte = caseByte,
                firstCluster = newCluster,
                fileSize = 0,
                dosDate = dosDate,
                dosTime = dosTime,
            )
            captureRegion(undoLog, parentLoc.offset, parentLoc.bytes.size)
            writeBytes(device, parentLoc.offset, dirBytes)

            undoLog.commit()
            return Fat12Result.Ok(Unit)
        } catch (e: FatVerifyFailedException) {
            undoLog.rollback()
            throw e
        } catch (e: IOException) {
            undoLog.rollback()
            throw FatWriteFailedException(e)
        } catch (e: Throwable) {
            undoLog.rollback()
            throw e
        }
    }

    // ----- rename (same-dir rename, LFN preamble, undo-log-wrapped) ----------

    /**
     * Rename the entry at [fromPath] to [toName] (same-directory rename only — a
     * cross-directory move is deferred to v2). Only the NAME changes: the
     * entry's first cluster, file size, attributes, and timestamps are preserved
     * exactly (a same-dir rename does NOT touch `..`).
     *
     * If [toName] exceeds a clean 8.3 form the new entry carries an LFN preamble
     * (reverse-ordinal, `ordinal | 0x40` on the highest slot, identical checksum —
     * via [DirRegion.encodeLfnPreamble]). The old entry's slots (preamble +
     * short) are marked 0xE5 and the new name's slots are written into the same
     * directory region under an [UndoLog]: any mid-op failure rolls the directory
     * region back byte-for-byte.
     *
     * Errors: the source missing → [Fat12Result.NotFound]; an empty [toName] →
     * [Fat12Result.InvalidName]; a different sibling already named [toName] →
     * [Fat12Result.NameConflict]; the parent path missing → [Fat12Result.NotFound].
     */
    fun rename(fromPath: String, toName: String): Fat12Result<Unit> {
        if (toName.isEmpty() || toName.contains('/')) {
            return Fat12Result.InvalidName(toName, "empty or path-bearing rename target")
        }
        val parentPath = parentOf(fromPath)
        val fromLeaf = leafOf(fromPath)
        val parentLoc = locateDir(parentPath) ?: return Fat12Result.NotFound(parentPath)

        val undoLog = UndoLog(device)
        try {
            val dirBytes = parentLoc.bytes.copyOf()
            val entry = findEntryByName(dirBytes, fromLeaf)
                ?: return Fat12Result.NotFound(fromPath)

            // Name-conflict guard: a DIFFERENT sibling already named toName.
            val (short83, caseByte) = DirRegion.shortName83(toName)
            val conflict = findEntryByName(dirBytes, toName) ?: findEntryByName(dirBytes, short83)
            if (conflict != null && conflict.shortEntryOffset != entry.shortEntryOffset) {
                return Fat12Result.NameConflict(toName)
            }

            // Preserve everything except the name. Re-read the existing entry's
            // metadata straight from the on-disk bytes so timestamps survive verbatim.
            val srcOff = entry.shortEntryOffset
            val attr = dirBytes[srcOff + 0x0B].toInt() and 0xFF
            val crtTime = u16(dirBytes, srcOff + 0x0E)
            val crtDate = u16(dirBytes, srcOff + 0x10)
            val lstAccDate = u16(dirBytes, srcOff + 0x12)
            val wrtTime = u16(dirBytes, srcOff + 0x16)
            val wrtDate = u16(dirBytes, srcOff + 0x18)
            val firstCluster = entry.firstCluster
            val fileSize = entry.fileSize

            val needsLfn = decideNeedsLfn(toName, short83)
            val lfnEntries = if (needsLfn) DirRegion.encodeLfnPreamble(toName, short83) else emptyList()

            // Delete the old slots (preamble + short), then place the renamed entry.
            // To keep the placement deterministic and avoid needing a contiguous gap
            // separate from the freed slots, reuse the freed region: mark old slots
            // 0xE5, then allocate a fresh run (the freed slots qualify as available).
            DirRegion.markDeleted(dirBytes, srcOff, entry.lfnEntryCount)

            val slotsNeeded = lfnEntries.size + 1
            val dstOffset = DirRegion.allocateEntry(dirBytes, slotsNeeded)
            if (dstOffset < 0) return Fat12Result.DiskFull

            var slot = dstOffset
            for (lfn in lfnEntries) {
                lfn.copyInto(dirBytes, slot)
                slot += DirRegion.ENTRY_SIZE
            }
            DirRegion.writeShortEntryGeneric(
                dirBytes = dirBytes,
                offset = slot,
                name83 = short83,
                attr = attr,
                caseByte = caseByte,
                firstCluster = firstCluster,
                fileSize = fileSize,
                dosDate = wrtDate,
                dosTime = wrtTime,
            )
            // Restore the create/last-access timestamps verbatim (writeShortEntryGeneric
            // derives them from the write date/time; rename must not mutate them).
            u16(dirBytes, slot + 0x0E, crtTime)
            u16(dirBytes, slot + 0x10, crtDate)
            u16(dirBytes, slot + 0x12, lstAccDate)
            u16(dirBytes, slot + 0x16, wrtTime)
            u16(dirBytes, slot + 0x18, wrtDate)

            captureRegion(undoLog, parentLoc.offset, parentLoc.bytes.size)
            writeBytes(device, parentLoc.offset, dirBytes)

            undoLog.commit()
            return Fat12Result.Ok(Unit)
        } catch (e: FatVerifyFailedException) {
            undoLog.rollback()
            throw e
        } catch (e: IOException) {
            undoLog.rollback()
            throw FatWriteFailedException(e)
        } catch (e: Throwable) {
            undoLog.rollback()
            throw e
        }
    }

    // ----- delete (post-order recursive, undo-log-wrapped) -------------------

    /**
     * Delete the file or directory at [path]. A non-empty directory requires
     * [recursive] = true; otherwise [Fat12Result.InvalidName] is returned (the
     * closest sealed variant).
     *
     * Recursive delete is POST-ORDER: every child's cluster chain
     * is freed BEFORE the parent directory's own chain, so each cluster is freed
     * exactly once — no double-free, no leaked clusters. Free-space accounting
     * therefore balances: countFreeClusters after == before + total clusters freed.
     *
     * The WHOLE compound delete is all-or-nothing: every touched FAT + dir
     * sector is captured in a single [UndoLog] before its first write, and any
     * mid-op failure rolls the entire tree back byte-for-byte to its pre-op state.
     * Write ordering keeps FAT writes before the
     * parent-dir-entry write (the commit point) — a worst case leaks clusters, never
     * orphans a live directory entry.
     *
     * Errors: the target missing → [Fat12Result.NotFound]; a non-empty directory
     * with `recursive == false` → [Fat12Result.InvalidName].
     */
    fun delete(path: String, recursive: Boolean = false): Fat12Result<Unit> {
        val parentPath = parentOf(path)
        val leaf = leafOf(path)
        if (leaf.isEmpty()) return Fat12Result.InvalidName(path, "empty name")
        val parentLoc = locateDir(parentPath) ?: return Fat12Result.NotFound(parentPath)

        val undoLog = UndoLog(device)
        try {
            // Working copy of the FAT mutated in memory across the whole tree, then
            // written ONCE (FAT[0], FAT[1]) before the parent-dir commit.
            val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
            val dirBytes = parentLoc.bytes.copyOf()
            val entry = findEntryByName(dirBytes, leaf)
                ?: return Fat12Result.NotFound(path)

            val isDir = (entry.attr and DirRegion.ATTR_DIRECTORY) != 0

            if (isDir && !recursive && !isDirectoryEmpty(fat, entry.firstCluster)) {
                return Fat12Result.InvalidName(path, "directory not empty")
            }

            if (isDir && entry.firstCluster >= 2) {
                // POST-ORDER: free all descendants' chains first, then this dir's chain.
                freeSubtreeChildren(fat, entry.firstCluster, undoLog)
                FatTable.freeChain(fat, entry.firstCluster)
            } else if (!isDir && entry.firstCluster >= 2) {
                FatTable.freeChain(fat, entry.firstCluster)
            }

            // Mark the entry's slots deleted in the parent (in-memory).
            DirRegion.markDeleted(dirBytes, entry.shortEntryOffset, entry.lfnEntryCount)

            // ---- FAT[0] then FAT[1] (dual-FAT sync, FAT before dir) -----------
            captureRegion(undoLog, bpb.fat0Offset, bpb.fatBytes)
            writeBytes(device, bpb.fat0Offset, fat)
            if (bpb.numFATs >= 2) {
                captureRegion(undoLog, bpb.fat1Offset, bpb.fatBytes)
                writeBytes(device, bpb.fat1Offset, fat)
            }

            // ---- write the PARENT directory entry LAST (commit point) ---------
            captureRegion(undoLog, parentLoc.offset, parentLoc.bytes.size)
            writeBytes(device, parentLoc.offset, dirBytes)

            undoLog.commit()
            return Fat12Result.Ok(Unit)
        } catch (e: FatVerifyFailedException) {
            undoLog.rollback()
            throw e
        } catch (e: IOException) {
            undoLog.rollback()
            throw FatWriteFailedException(e)
        } catch (e: Throwable) {
            undoLog.rollback()
            throw e
        }
    }

    /**
     * Free every DESCENDANT cluster chain of the directory whose chain starts at
     * [dirCluster], POST-ORDER: recurse into each child subdirectory (freeing its
     * descendants, then itself) BEFORE returning, and free each child file's chain.
     * Does NOT free [dirCluster]'s own chain (the caller frees the parent last —
     * post-order). `.` and `..` reserved entries are skipped so a cluster is never
     * visited twice (no double-free).
     */
    private fun freeSubtreeChildren(fat: ByteArray, dirCluster: Int, undoLog: UndoLog) {
        val dirChain = FatTable.walkChain(fat, dirCluster)
        // Capture the directory's own cluster sectors (their entry slots are read;
        // they will be reclaimed when the parent frees this chain — capture so a
        // later rollback restores them exactly).
        for (c in dirChain) captureRegion(undoLog, bpb.byteOffsetOfCluster(c), bpb.clusterSize)

        val dirBuf = ByteArray(dirChain.size * bpb.clusterSize)
        for ((i, c) in dirChain.withIndex()) {
            readBytes(device, bpb.byteOffsetOfCluster(c), bpb.clusterSize)
                .copyInto(dirBuf, i * bpb.clusterSize)
        }
        for (child in DirRegion.walk(dirBuf)) {
            val name = child.shortName83.trim()
            if (name == "." || name == "..") continue   // never recurse into the reserved entries
            val childIsDir = (child.attr and DirRegion.ATTR_DIRECTORY) != 0
            if (childIsDir && child.firstCluster >= 2) {
                freeSubtreeChildren(fat, child.firstCluster, undoLog)   // children first (post-order)
                FatTable.freeChain(fat, child.firstCluster)             // then the child dir itself
            } else if (!childIsDir && child.firstCluster >= 2) {
                FatTable.freeChain(fat, child.firstCluster)
            }
        }
    }

    /** True when the directory whose chain starts at [dirCluster] holds no real children (only `.`/`..`). */
    private fun isDirectoryEmpty(fat: ByteArray, dirCluster: Int): Boolean {
        if (dirCluster < 2) return true
        val chain = FatTable.walkChain(fat, dirCluster)
        val buf = ByteArray(chain.size * bpb.clusterSize)
        for ((i, c) in chain.withIndex()) {
            readBytes(device, bpb.byteOffsetOfCluster(c), bpb.clusterSize)
                .copyInto(buf, i * bpb.clusterSize)
        }
        return DirRegion.walk(buf).none {
            val n = it.shortName83.trim()
            n != "." && n != ".."
        }
    }

    /** Read [region.size] bytes at [offset] and byte-compare against [region]; throw on mismatch. */
    private fun verifyRegion(offset: Long, region: ByteArray) {
        val readBack = readBytes(device, offset, region.size)
        for (j in region.indices) {
            if (readBack[j] != region[j]) {
                throw FatVerifyFailedException(
                    sectorOffset = offset + j,
                    expected = region[j],
                    actual = readBack[j],
                )
            }
        }
    }

    // ----- set volume label (VOL-05 — in-place, undo-log-wrapped) ------------

    /**
     * VOL-05: rewrite the volume label IN PLACE under the INT-02 verify-after-write
     * + rollback contract. Writes BOTH on-disk label locations so SC#1 ("reflected
     * immediately … persists after re-mounting") holds for every reader:
     *   - BS_VolLab at boot-sector offset [BS_VOLLAB_OFFSET] (0x2B), the copy
     *     [volumeLabel] reads back; and
     *   - the root [ATTR_VOLUME_ID] (0x08) directory entry — updated in place when
     *     present, else CREATED in a free root slot (firstCluster = 0, fileSize = 0),
     *     the copy most external tools read.
     *
     * Non-destructive: only the label bytes change (no FAT / cluster allocation).
     * Both regions are captured in a single [UndoLog] before their write and
     * verify-read after; any mid-op fault rolls the volume back byte-for-byte
     * (INT-02 / T-04-02) and re-throws.
     *
     * An EMPTY/blank label is VALID (W3 / RESEARCH Q1): it normalizes to 11 spaces
     * (clears the label), which [volumeLabel] trimEnds back to "". Only a
     * '/'-bearing or over-long (> [VOL_LAB_LEN]) label is rejected as
     * [Fat12Result.InvalidName] (T-04-01), BEFORE any byte is written.
     */
    fun setVolumeLabel(label: String): Fat12Result<Unit> {
        if (label.contains('/') || label.length > VOL_LAB_LEN) {
            return Fat12Result.InvalidName(label, "label must be <= 11 chars, no '/'")
        }
        // NOTE: do NOT reject an empty/blank label — padEnd below clears it to 11
        // spaces (W3). Normalization matches Fat12Formatter exactly.
        val label11 = label.uppercase().padEnd(VOL_LAB_LEN, ' ').take(VOL_LAB_LEN)
        val labelBytes = label11.toByteArray(Charsets.US_ASCII)

        val undoLog = UndoLog(device)
        try {
            // ---- Region A: BS_VolLab at boot-sector 0x2B ---------------------
            val sector0 = readBytes(device, 0L, bpb.bytsPerSec)
            captureRegion(undoLog, 0L, bpb.bytsPerSec)
            labelBytes.copyInto(sector0, BS_VOLLAB_OFFSET, 0, VOL_LAB_LEN)
            writeBytes(device, 0L, sector0)
            verifyRegion(0L, sector0)

            // ---- Region B: root ATTR_VOLUME_ID entry (update or create) ------
            val rootBytes = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
            val volId = DirRegion.walk(rootBytes)
                .firstOrNull { (it.attr and ATTR_VOLUME_ID) != 0 }
            if (volId != null) {
                // Overwrite the 11 name bytes in place; attr/cluster/size untouched.
                labelBytes.copyInto(rootBytes, volId.shortEntryOffset, 0, VOL_LAB_LEN)
            } else {
                val slot = DirRegion.allocateEntry(rootBytes, 1)
                if (slot < 0) {
                    undoLog.rollback()
                    return Fat12Result.DiskFull
                }
                val (dosDate, dosTime) = DirRegion.currentDosDateTime()
                DirRegion.writeShortEntryGeneric(
                    dirBytes = rootBytes,
                    offset = slot,
                    name83 = label11,
                    attr = ATTR_VOLUME_ID,
                    caseByte = 0,
                    firstCluster = 0,
                    fileSize = 0,
                    dosDate = dosDate,
                    dosTime = dosTime,
                )
            }
            captureRegion(undoLog, bpb.rootDirOffset, bpb.rootDirBytes)
            writeBytes(device, bpb.rootDirOffset, rootBytes)
            verifyRegion(bpb.rootDirOffset, rootBytes)

            undoLog.commit()
            return Fat12Result.Ok(Unit)
        } catch (e: FatVerifyFailedException) {
            undoLog.rollback()
            throw e
        } catch (e: IOException) {
            undoLog.rollback()
            throw FatWriteFailedException(e)
        } catch (e: Throwable) {
            undoLog.rollback()
            throw e
        }
    }

    // ----- set attributes (FIL-06 — in-place, undo-log-wrapped) --------------

    /**
     * FIL-06: rewrite the user-settable attribute bits of the file/folder at
     * [path] IN PLACE under the INT-02 verify-after-write + rollback contract
     * (structurally modelled on [setVolumeLabel]).
     *
     * Only the four user bits are written: READ_ONLY (0x01), HIDDEN (0x02),
     * SYSTEM (0x04), ARCHIVE (0x20) — the [USER_ATTR_MASK]. The non-user bits
     * ATTR_VOLUME_ID (0x08) and ATTR_DIRECTORY (0x10) — the [PRESERVE_ATTR_MASK]
     * — are PRESERVED from the existing byte, so a caller can never flip a
     * folder into a file, clear the directory bit, or set the volume-ID bit
     * (Pitfall 4 / T-05-01). The LFN composite (0x0F) never reaches here because
     * [findEntryByName] resolves only short entries.
     *
     * Returns [Fat12Result.NotFound] (BEFORE touching any byte) when the parent
     * directory or the leaf entry does not exist. The single touched region is
     * the PARENT directory region (root or a subdir chain): it is captured in an
     * [UndoLog], written in place, and verify-read; any mid-op fault rolls the
     * volume back byte-for-byte (INT-02 / T-05-02) and re-throws.
     */
    fun setAttributes(path: String, attrs: Int): Fat12Result<Unit> {
        val parentLoc = locateDir(parentOf(path)) ?: return Fat12Result.NotFound(parentOf(path))
        val dirBytes = parentLoc.bytes.copyOf()
        val entry = findEntryByName(dirBytes, leafOf(path)) ?: return Fat12Result.NotFound(path)

        // Rewrite ONLY the attr byte at the short entry's offset 0x0B, preserving
        // the DIRECTORY / VOLUME_ID bits and flipping only the user R/H/S/A bits.
        val off = entry.shortEntryOffset + 0x0B
        val existing = dirBytes[off].toInt() and 0xFF
        val newByte = (existing and PRESERVE_ATTR_MASK) or (attrs and USER_ATTR_MASK)
        dirBytes[off] = newByte.toByte()

        val undoLog = UndoLog(device)
        try {
            captureRegion(undoLog, parentLoc.offset, parentLoc.bytes.size)
            writeBytes(device, parentLoc.offset, dirBytes)
            verifyRegion(parentLoc.offset, dirBytes)
            undoLog.commit()
            return Fat12Result.Ok(Unit)
        } catch (e: FatVerifyFailedException) {
            undoLog.rollback()
            throw e
        } catch (e: IOException) {
            undoLog.rollback()
            throw FatWriteFailedException(e)
        } catch (e: Throwable) {
            undoLog.rollback()
            throw e
        }
    }

    // ----- format delegation -------------------------------------------------

    /** Format the device as FAT12 from scratch (delegates to [Fat12Formatter]). */
    fun format(volumeLabel: String = "NO NAME    "): Fat12Result<Unit> =
        Fat12Formatter(device).format(volumeLabel)

    // ----- private helpers ---------------------------------------------------

    /**
     * Capture every sector covered by the [length]-byte region starting at
     * [byteOffset] into the undo-log, BEFORE the region is written. A region may
     * span several sectors (a cluster, a multi-sector FAT, the root-dir region),
     * so we snapshot each sector individually so rollback restores exactly the
     * touched sectors.
     */
    private fun captureRegion(undoLog: UndoLog, byteOffset: Long, length: Int) {
        val sectorSize = bpb.bytsPerSec
        val firstSector = byteOffset / sectorSize
        val lastSector = (byteOffset + length - 1) / sectorSize
        for (s in firstSector..lastSector) {
            undoLog.captureIfAbsent(s * sectorSize, sectorSize)
        }
    }

    /**
     * Location of an on-disk directory region: its byte offset, a working copy of its
     * bytes, and the directory's OWN first cluster ([DirRegion.ROOT_DIR_MARKER] =
     * 0x0000 for the root region). [firstCluster] is what a child's `..` entry must
     * point at when this directory is the parent.
     */
    private data class DirLocation(val offset: Long, val bytes: ByteArray, val firstCluster: Int)

    /** Read the directory region for [path] for LISTING (returns null on NotFound). */
    private fun readDirBytes(path: String): ByteArray? = locateDir(path)?.bytes

    /**
     * Resolve a directory [path] to its on-disk location. Root ("", "/") is the
     * fixed root-dir region. A nested path is resolved component-by-component:
     * each component must name a subdirectory whose cluster chain is then read.
     */
    private fun locateDir(path: String): DirLocation? {
        val components = path.split('/').filter { it.isNotEmpty() }
        // Root directory region.
        var bytes = readBytes(device, bpb.rootDirOffset, bpb.rootDirBytes)
        var offset = bpb.rootDirOffset
        var firstCluster = DirRegion.ROOT_DIR_MARKER

        if (components.isEmpty()) return DirLocation(offset, bytes, firstCluster)

        val fat = readBytes(device, bpb.fat0Offset, bpb.fatBytes)
        for (comp in components) {
            val entry = DirRegion.findEntry(bytes, comp)
                ?: resolveByLfn(bytes, comp)
                ?: return null
            if ((entry.attr and DirRegion.ATTR_DIRECTORY) == 0) return null
            if (entry.firstCluster < 2) return null
            // Read the subdirectory's cluster chain into one contiguous buffer.
            val chain = FatTable.walkChain(fat, entry.firstCluster)
            if (chain.isEmpty()) return null
            val buf = ByteArray(chain.size * bpb.clusterSize)
            for ((i, cluster) in chain.withIndex()) {
                val cb = readBytes(device, bpb.byteOffsetOfCluster(cluster), bpb.clusterSize)
                cb.copyInto(buf, i * bpb.clusterSize)
            }
            bytes = buf
            offset = bpb.byteOffsetOfCluster(chain.first())
            firstCluster = entry.firstCluster
        }
        return DirLocation(offset, bytes, firstCluster)
    }

    /**
     * Resolve the parent directory bytes + the leaf name for a file [path].
     * Returns null when the parent directory does not exist.
     */
    private fun resolveParent(path: String): Pair<ByteArray, String>? {
        val parent = locateDir(parentOf(path)) ?: return null
        return parent.bytes to leafOf(path)
    }

    /** Find an entry whose LFN-decoded display name equals [name] (case-insensitive). */
    private fun resolveByLfn(dirBytes: ByteArray, name: String): DirRegion.WalkedEntry? {
        return DirRegion.walk(dirBytes).firstOrNull {
            decodeLfnName(dirBytes, it).equals(name, ignoreCase = true)
        }
    }

    /**
     * Resolve [name] against an entry by ANY of its names, case-insensitively: the
     * raw 11-char 8.3 short name, the trimmed short name, the dotted display form
     * ("DATA.BIN"), or the LFN display name. This lets callers pass the natural
     * user-facing name ("DATA.BIN") and still hit the on-disk "DATA    BIN".
     */
    private fun findEntryByName(dirBytes: ByteArray, name: String): DirRegion.WalkedEntry? {
        val target = name.uppercase()
        return DirRegion.walk(dirBytes).firstOrNull { e ->
            e.shortName83.equals(name, ignoreCase = true) ||
                e.shortName83.trim().equals(name, ignoreCase = true) ||
                decode83(e.shortName83).equals(target, ignoreCase = true) ||
                decodeLfnName(dirBytes, e).equals(name, ignoreCase = true)
        }
    }

    /** Map an internal [DirRegion.WalkedEntry] to the public [Fat12Entry], resolving the LFN name. */
    private fun toFat12Entry(dirBytes: ByteArray, e: DirRegion.WalkedEntry): Fat12Entry {
        val display = decodeLfnName(dirBytes, e)
        val short83 = e.shortName83
        return Fat12Entry(
            name = display.ifEmpty { decode83(short83) },
            shortName = short83,
            isDirectory = (e.attr and DirRegion.ATTR_DIRECTORY) != 0,
            size = e.fileSize.toLong(),
            firstCluster = e.firstCluster,
            attributes = e.attr,
            createdDate = u16(dirBytes, e.shortEntryOffset + 0x10),
            modifiedDate = u16(dirBytes, e.shortEntryOffset + 0x18),
            modifiedTime = u16(dirBytes, e.shortEntryOffset + 0x16),
        )
    }

    /**
     * Decode the LFN display name from the `lfnEntryCount` preamble entries that
     * precede [e]'s short entry, or "" when there is no LFN preamble. Slots are
     * stored physically reverse-ordinal; we reconstruct by ordinal so the result
     * is robust to physical layout. The payload terminates at the first 0x0000
     * code unit; 0xFFFF code units are trailing padding and skipped.
     */
    private fun decodeLfnName(dirBytes: ByteArray, e: DirRegion.WalkedEntry): String {
        if (e.lfnEntryCount <= 0) return ""
        val preambleStart = e.shortEntryOffset - e.lfnEntryCount * DirRegion.ENTRY_SIZE
        val ordered = (0 until e.lfnEntryCount)
            .map { k -> preambleStart + k * DirRegion.ENTRY_SIZE }
            .sortedBy { off -> dirBytes[off].toInt() and 0x3F }  // ascending ordinal (strip 0x40 flag)

        val out = StringBuilder()
        for (off in ordered) {
            for (code in decodeLfnSlotCodes(dirBytes, off)) {
                if (code == 0x0000) return out.toString()  // LFN terminator
                if (code == 0xFFFF) continue                 // trailing padding
                out.append(code.toChar())
            }
        }
        return out.toString()
    }

    /** Extract the 13 UTF-16LE code points from one LFN slot at [off]. */
    private fun decodeLfnSlotCodes(dirBytes: ByteArray, off: Int): IntArray {
        val positions = intArrayOf(0x01, 0x03, 0x05, 0x07, 0x09, 0x0E, 0x10, 0x12, 0x14, 0x16, 0x18, 0x1C, 0x1E)
        return IntArray(positions.size) { i ->
            val p = positions[i]
            val lo = dirBytes[off + p].toInt() and 0xFF
            val hi = dirBytes[off + p + 1].toInt() and 0xFF
            (hi shl 8) or lo
        }
    }

    /** Decode an 11-byte 8.3 short name into a "NAME.EXT" display string. */
    private fun decode83(short83: String): String {
        val base = short83.substring(0, 8).trimEnd()
        val ext = if (short83.length >= 11) short83.substring(8, 11).trimEnd() else ""
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** Concatenate the streamed chunks into a single contiguous payload. */
    private fun concatChunks(source: Sequence<ByteArray>): ByteArray {
        val parts = source.toList()
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /** Does [leaf] require an LFN preamble (i.e. it is not a clean uppercase 8.3 name)? */
    private fun decideNeedsLfn(leaf: String, short83: String): Boolean {
        // A name needs an LFN when its 8.3 short form is not a lossless uppercase
        // round-trip of the original (mixed case, long names, or '~1' mangling).
        return decode83(short83).uppercase() != leaf.uppercase() || leaf != leaf.uppercase()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** Little-endian 16-bit write (the inverse of the [u16] read above). */
    private fun u16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private companion object {
        /** BS_VolLab (volume label) lives at BPB offset 43 (0x2B), 11 bytes. */
        const val BS_VOLLAB_OFFSET: Int = 0x2B
        const val VOL_LAB_LEN: Int = 11

        /** Directory entry attribute for the volume-label entry (skipped in list()). */
        const val ATTR_VOLUME_ID: Int = 0x08

        /** User-settable attribute bits (FIL-06): R 0x01 | H 0x02 | S 0x04 | A 0x20. */
        const val USER_ATTR_MASK: Int = 0x27

        /** Non-user attribute bits preserved across setAttributes: VOLUME_ID 0x08 | DIRECTORY 0x10. */
        const val PRESERVE_ATTR_MASK: Int = 0x18

        fun parentOf(path: String): String {
            val trimmed = path.trim('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx < 0) "" else trimmed.substring(0, idx)
        }

        fun leafOf(path: String): String = path.trim('/').substringAfterLast('/')
    }
}
