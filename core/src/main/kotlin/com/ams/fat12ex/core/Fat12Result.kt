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
    object DiskFull : Fat12Result<Nothing>() {
        // Stable, human-readable rendering (an `object` otherwise inherits the
        // Foo@hash default) so getOrThrow()'s message reads "...not Ok: DiskFull".
        override fun toString(): String = "DiskFull"
    }
    data class TooLarge(val actualBytes: Long) : Fat12Result<Nothing>()
    data class NotFound(val path: String) : Fat12Result<Nothing>()
    data class InvalidName(val name: String, val reason: String) : Fat12Result<Nothing>()
}

/** True when this result is [Fat12Result.Ok]. */
val Fat12Result<*>.isOk: Boolean get() = this is Fat12Result.Ok

/** True when this result is any non-[Fat12Result.Ok] outcome. */
val Fat12Result<*>.isError: Boolean get() = this !is Fat12Result.Ok

/** The success value, or `null` for any non-[Fat12Result.Ok] outcome. */
fun <T> Fat12Result<T>.getOrNull(): T? = (this as? Fat12Result.Ok)?.value

/**
 * The success value, or throw [IllegalStateException] describing the non-[Fat12Result.Ok]
 * outcome. Use only when a non-Ok result is a programming error at the call site; prefer
 * [getOrNull] or an exhaustive `when` for recoverable handling.
 */
fun <T> Fat12Result<T>.getOrThrow(): T =
    when (this) {
        is Fat12Result.Ok -> value
        else -> error("Fat12Result was not Ok: $this")
    }
