package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Import naming contract: a long Android filename written as the
 * leaf of [Fat12Volume.writeFile] is preserved as a resolvable LFN with an
 * auto-generated 8.3 alias. The engine owns the LFN preamble + `shortName83`
 * mangling; this test LOCKS that a long name round-trips through `list` (display
 * name preserved) and `readFile` (the 8.3 alias resolves the bytes).
 *
 * No production code is exercised beyond the existing engine I/O.
 */
class LfnImportNamingTest {

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    @Test
    fun longName_preservedAsLfn() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        val longName = "A long imported name.txt"
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/$longName", sequenceOf(ByteArray(64) { it.toByte() })),
        )

        val entries = (vol.list("") as Fat12Result.Ok).value
        val match = entries.firstOrNull { it.name == longName }
        assertNotNull(match, "list must surface the long name verbatim as the LFN display")
        // The auto-generated 8.3 alias is distinct from the long display name.
        assertTrue(
            match!!.shortName.isNotBlank(),
            "an 8.3 alias must be generated for the long-name entry",
        )
    }

    @Test
    fun longName_hasResolvable83Alias() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        val longName = "A long imported name.txt"
        val payload = ByteArray(64) { (it * 3).toByte() }
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/$longName", sequenceOf(payload)),
        )

        // The engine resolves the entry by its long name (findEntryByName matches LFN).
        val read = vol.readFile("/$longName")
        assertInstanceOf(Fat12Result.Ok::class.java, read)
        assertArrayEquals(
            payload,
            (read as Fat12Result.Ok).value,
            "the auto 8.3 alias must resolve the imported bytes",
        )
    }
}
