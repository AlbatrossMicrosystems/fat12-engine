package com.ams.fat12ex.core

/**
 * Public directory-listing entry — the type the Wave 3 [Fat12Volume] facade's
 * `list()` returns to callers (and ultimately the Compose UI).
 *
 * Derived from the internal [DirRegion.WalkedEntry] shape (which carries on-disk
 * offsets for mutation) but stripped of those internal fields and enriched with a
 * resolved LFN [name] and timestamp metadata for presentation.
 *
 * - [name]       LFN display name if an LFN preamble was present, else the decoded 8.3 name.
 * - [shortName]  raw 11-char 8.3 short name (e.g. "FIRMWAREBIN").
 * - [isDirectory] true when the FAT `ATTR_DIRECTORY` (0x10) bit is set.
 * - [size]       file size in bytes (0 for directories).
 * - [firstCluster] first cluster of the entry's chain (0 for empty / `..`-of-root).
 * - [attributes] raw FAT attribute byte at offset 0x0B.
 * - [createdDate]/[modifiedDate]/[modifiedTime] DOS-packed date/time fields.
 */
data class Fat12Entry(
    val name: String,          // LFN if present, else decoded 8.3
    val shortName: String,     // 11-char 8.3 (e.g. "FIRMWAREBIN")
    val isDirectory: Boolean,
    val size: Long,
    val firstCluster: Int,
    val attributes: Int,       // raw FAT attr byte
    val createdDate: Int,
    val modifiedDate: Int,
    val modifiedTime: Int,
)
