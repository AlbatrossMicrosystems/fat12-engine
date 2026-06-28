package com.ams.fat12ex.core

/**
 * Public FAT directory-entry attribute bits — the attribute byte at directory-entry
 * offset `0x0B` (per the FAT specification).
 *
 * These let callers of [Fat12Volume.setAttributes] and readers of [Fat12Entry.attributes]
 * use named constants instead of hard-coding magic numbers like `0x01` / `0x02`. The
 * four user-settable bits ([READ_ONLY], [HIDDEN], [SYSTEM], [ARCHIVE]) are the only ones
 * `setAttributes` writes; [VOLUME_ID] and [DIRECTORY] are exposed read-only for callers
 * inspecting [Fat12Entry.attributes] (the engine preserves them — see [Fat12Volume.setAttributes]).
 *
 * Bits combine with `or`, e.g. `READ_ONLY or HIDDEN == 0x03`.
 */
object FatAttributes {
    /** Read-only (R) — bit 0. */
    const val READ_ONLY: Int = 0x01

    /** Hidden (H) — bit 1. */
    const val HIDDEN: Int = 0x02

    /** System (S) — bit 2. */
    const val SYSTEM: Int = 0x04

    /** Volume-label entry (read-only here; never user-settable via [Fat12Volume.setAttributes]). */
    const val VOLUME_ID: Int = 0x08

    /** Directory entry (read-only here; preserved by [Fat12Volume.setAttributes]). */
    const val DIRECTORY: Int = 0x10

    /** Archive (A) — bit 5. */
    const val ARCHIVE: Int = 0x20
}
