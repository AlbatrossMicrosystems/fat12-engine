package com.ams.fat12ex.core

/**
 * Typed exception hierarchy for the FAT12 read/write layer.
 *
 * The activity catches these at its top boundary and renders user-visible
 * messages from the typed fields (no string parsing required).
 *
 * The hint message in [NotFat12Exception] carries geometry diagnostics only
 * (cluster count, BS_FilSysType label); the app-specific bl-mode hint of the
 * source engine is intentionally dropped — the binding FAT12 check is the
 * cluster count, not the on-disk label.
 */

class NotFat12Exception(
    val actualFsType: String,
    val hint: String,
) : Exception("Not FAT12: got '$actualFsType'. $hint")

class FatVerifyFailedException(
    val sectorOffset: Long,
    val expected: Byte,
    val actual: Byte,
) : Exception("Verify-read mismatch at sector offset $sectorOffset: expected 0x${"%02X".format(expected)}, got 0x${"%02X".format(actual)}")

class FatWriteFailedException(cause: Throwable) : Exception("FAT write failed: ${cause.message}", cause)

class NoFreeSpaceException(
    val needBytes: Long,
    val freeBytes: Long,
) : Exception("FAT volume has $freeBytes bytes free; need $needBytes bytes")

/**
 * Thrown when an on-disk FAT12 structure is internally inconsistent in a way
 * that cannot be safely interpreted. Carries a human-readable [detail]
 * describing the inconsistency for diagnostics.
 */
class CorruptedVolumeException(val detail: String) : Exception("FAT12 volume corruption detected: $detail")
