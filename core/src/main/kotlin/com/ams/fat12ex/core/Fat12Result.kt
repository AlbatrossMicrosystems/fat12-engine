package com.ams.fat12ex.core

/**
 * Sealed return type for recoverable FAT12 operation outcomes.
 *
 * The error surface is split in two: truly exceptional, programmer-or-media
 * faults are thrown (see [FatExceptions]); ordinary, recoverable outcomes the
 * UI should render as messages (name taken, disk full, file missing, …) are
 * returned as a [Fat12Result]. Wave 2 (formatter) and Wave 3 (the Fat12Volume
 * facade) consume this type as the return value of their public operations.
 */
sealed class Fat12Result<out T> {
    data class Ok<T>(val value: T) : Fat12Result<T>()
    data class NameConflict(val name: String) : Fat12Result<Nothing>()
    object DiskFull : Fat12Result<Nothing>()
    data class TooLarge(val actualBytes: Long) : Fat12Result<Nothing>()
    data class NotFound(val path: String) : Fat12Result<Nothing>()
    data class InvalidName(val name: String, val reason: String) : Fat12Result<Nothing>()
}
