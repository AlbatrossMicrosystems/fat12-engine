package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `setAttributes` NotFound-target consistency.
 *
 * When the PARENT directory of `path` does not exist, the returned
 * [Fat12Result.NotFound] must name the missing PARENT (`parentOf(path)`),
 * matching `writeFile`'s `NotFound(parentPath)` diagnostic style. When the
 * parent exists but the LEAF entry is missing, it must still name the full
 * `path` (the leaf is what's missing). Both guards run BEFORE any byte is
 * touched, so this is a diagnostic-consistency fix only.
 *
 * JUnit 5 + the `InMemoryBlockDevice` / `Fat12ImageBuilder` `:core` fixture
 * style (cf. [SetAttributesTest]).
 */
class SetAttributesNotFoundTest {

    private val ATTR_DIRECTORY = 0x10
    private val ATTR_ARCHIVE = 0x20
    private val ATTR_READ_ONLY = 0x01

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    /** A root with a plain file `DATA.BIN` and a subdir `SUBDIR`. */
    private fun imageWithFileAndDir(): InMemoryBlockDevice =
        Fat12ImageBuilder()
            .withReservedShortEntry(
                name83 = "DATA    BIN",
                attr = ATTR_ARCHIVE,
                clusters = listOf(2),
                bytes = ByteArray(16) { it.toByte() },
            )
            .withReservedShortEntry(
                name83 = "SUBDIR     ",
                attr = ATTR_DIRECTORY,
                clusters = listOf(3),
                bytes = ByteArray(16),
            )
            .build()

    @Test
    fun missingParent_returnsNotFoundNamingTheParent() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        // "/GHOSTDIR" does not exist, so the FILE.BIN under it has a missing PARENT.
        val result = vol.setAttributes("/GHOSTDIR/FILE.BIN", ATTR_READ_ONLY)

        val notFound = assertInstanceOf(Fat12Result.NotFound::class.java, result)
        // parentOf("/GHOSTDIR/FILE.BIN") trims the leading slash -> "GHOSTDIR".
        // (parentOf is a private companion helper, so we assert the literal value.)
        assertEquals(
            "GHOSTDIR",
            notFound.path,
            "a missing parent must name the parent path (MR-02), not the full leaf path",
        )
    }

    @Test
    fun missingLeaf_existingParent_returnsNotFoundNamingTheFullPath() {
        val device = imageWithFileAndDir()
        val vol = openVolume(device)

        // Parent (root) exists; only the leaf "NOPE.BIN" is missing.
        val path = "/NOPE.BIN"
        val result = vol.setAttributes(path, ATTR_READ_ONLY)

        val notFound = assertInstanceOf(Fat12Result.NotFound::class.java, result)
        assertEquals(
            path,
            notFound.path,
            "a missing leaf (parent present) must still name the full path",
        )
    }
}
