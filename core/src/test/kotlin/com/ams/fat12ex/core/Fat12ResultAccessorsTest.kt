package com.ams.fat12ex.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [Fat12Result] convenience-accessor tests — [isOk] / [isError] / [getOrNull] /
 * [getOrThrow].
 *
 * Proves the accessors branch on success without an exhaustive `when` or an
 * `is Fat12Result.Ok` cast, for both the [Fat12Result.Ok] case and a couple of
 * representative non-Ok variants ([Fat12Result.NotFound], [Fat12Result.DiskFull]).
 */
class Fat12ResultAccessorsTest {

    @Test
    fun ok_isOk_getOrNull_getOrThrow() {
        val result: Fat12Result<String> = Fat12Result.Ok("payload")
        assertTrue(result.isOk)
        assertFalse(result.isError)
        assertEquals("payload", result.getOrNull())
        assertEquals("payload", result.getOrThrow())
    }

    @Test
    fun notFound_isNotOk_getOrNull_null_getOrThrow_throws() {
        val result: Fat12Result<String> = Fat12Result.NotFound("/MISSING.TXT")
        assertFalse(result.isOk)
        assertTrue(result.isError)
        assertNull(result.getOrNull())
        assertThrows<IllegalStateException> { result.getOrThrow() }
    }

    @Test
    fun diskFull_isNotOk_getOrNull_null_getOrThrow_throws() {
        val result: Fat12Result<Unit> = Fat12Result.DiskFull
        assertFalse(result.isOk)
        assertTrue(result.isError)
        assertNull(result.getOrNull())
        assertThrows<IllegalStateException> { result.getOrThrow() }
    }

    @Test
    fun ok_withNullableValue_getOrNull_distinguishesNullValueFromError() {
        // An Ok wrapping a null value still reports isOk; getOrNull returns null here
        // too, but getOrThrow returns the (null) value rather than throwing.
        val result: Fat12Result<String?> = Fat12Result.Ok(null)
        assertTrue(result.isOk)
        assertNull(result.getOrNull())
        assertNull(result.getOrThrow())
    }
}
