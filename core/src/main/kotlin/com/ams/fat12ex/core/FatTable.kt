package com.ams.fat12ex.core

/**
 * FAT12 table operations — 12-bit pack/unpack + chain walk + alloc/free.
 *
 * Pure-byte-manipulation helpers operating on a `ByteArray` representing one
 * FAT copy. Callers (FatFlasher) are responsible for:
 *   - Reading FAT[0] from the device (Bpb.fatBytes bytes)
 *   - Mutating the array via these functions
 *   - Writing both FAT[0] and FAT[1] back, FAT[0] first (dual-FAT sync ordering)
 *
 * 12-bit packing scheme (compuphase.com/mbr_fat.htm):
 *   Two 12-bit entries per 3 bytes. Entry N at byte-offset = N + (N >> 1):
 *     N even → low 12 bits of (byte[off] | (byte[off+1] << 8))
 *     N odd  → high 12 bits of (byte[off] | (byte[off+1] << 8)) >> 4
 *
 * Reserved entries: f[0] = 0xFF8 (media descriptor mirror), f[1] = 0xFFF (EOC).
 */
object FatTable {

    const val FREE: Int = 0x000
    const val EOC: Int = 0xFFF
    const val EOC_MIN: Int = 0xFF8
    const val BAD: Int = 0xFF7

    fun read12(fat: ByteArray, n: Int): Int {
        require(n >= 0) { "Cluster index must be non-negative: $n" }
        val off = n + (n shr 1)
        require(off + 1 < fat.size) { "FAT entry $n out of range (off=$off, fat.size=${fat.size})" }
        val b0 = fat[off].toInt() and 0xFF
        val b1 = fat[off + 1].toInt() and 0xFF
        return if (n and 1 == 0) {
            (b0 or ((b1 and 0x0F) shl 8)) and 0xFFF
        } else {
            ((b0 ushr 4) or (b1 shl 4)) and 0xFFF
        }
    }

    fun write12(fat: ByteArray, n: Int, value: Int) {
        require(n >= 0) { "Cluster index must be non-negative: $n" }
        require(value in 0..0xFFF) { "FAT12 value must be 12-bit: 0x${value.toString(16)}" }
        val off = n + (n shr 1)
        require(off + 1 < fat.size) { "FAT entry $n out of range (off=$off, fat.size=${fat.size})" }
        if (n and 1 == 0) {
            fat[off] = (value and 0xFF).toByte()
            fat[off + 1] = ((fat[off + 1].toInt() and 0xF0) or ((value shr 8) and 0x0F)).toByte()
        } else {
            fat[off] = ((fat[off].toInt() and 0x0F) or ((value shl 4) and 0xF0)).toByte()
            fat[off + 1] = ((value shr 4) and 0xFF).toByte()
        }
    }

    fun isEoc(value: Int): Boolean = value in EOC_MIN..0xFFF

    fun walkChain(fat: ByteArray, startCluster: Int, maxClusters: Int = 65535): List<Int> {
        require(startCluster >= 2) { "Cluster numbers start at 2 in FAT12; got $startCluster" }
        // Unallocated cluster (entry value FREE) is not part of any chain.
        // Single-cluster chains (entry value EOC) ARE valid and walk-includes the start.
        if (read12(fat, startCluster) == FREE) return emptyList()
        val chain = mutableListOf<Int>()
        var c = startCluster
        var iter = 0
        while (!isEoc(c) && c != FREE && c != BAD) {
            if (iter++ > maxClusters) {
                throw IllegalStateException("walkChain exceeded $maxClusters iterations starting at $startCluster — FAT corruption suspected")
            }
            chain.add(c)
            c = read12(fat, c)
        }
        return chain
    }

    fun freeChain(fat: ByteArray, startCluster: Int): Int {
        if (startCluster < 2) return 0
        val chain = walkChain(fat, startCluster)
        for (c in chain) write12(fat, c, FREE)
        return chain.size
    }

    fun countFreeClusters(fat: ByteArray, totalClusters: Int): Int {
        var free = 0
        for (n in 2 until totalClusters + 2) {
            if (read12(fat, n) == FREE) free++
        }
        return free
    }

    fun allocateChain(fat: ByteArray, clusterCount: Int, totalClusters: Int): List<Int> {
        require(clusterCount > 0) { "clusterCount must be positive: $clusterCount" }
        val picked = mutableListOf<Int>()
        for (n in 2 until totalClusters + 2) {
            if (read12(fat, n) == FREE) {
                picked.add(n)
                if (picked.size == clusterCount) break
            }
        }
        if (picked.size < clusterCount) {
            throw NoFreeSpaceException(
                needBytes = clusterCount.toLong(),
                freeBytes = picked.size.toLong(),
            )
        }
        for (i in picked.indices) {
            val next = if (i == picked.lastIndex) EOC else picked[i + 1]
            write12(fat, picked[i], next)
        }
        return picked
    }
}
