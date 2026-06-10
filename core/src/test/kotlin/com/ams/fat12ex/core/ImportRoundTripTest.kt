package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Import/export byte-identity contract (FIL-07 / FIL-08, SC#2 / SC#3).
 *
 * Import/export are pure wiring over the proven [Fat12Volume.writeFile]
 * (import target) and [Fat12Volume.readFile] (export/share source) engine APIs.
 * These tests LOCK the byte-identity guarantee the `:app` glue depends on:
 * bytes written via `writeFile` read back byte-for-byte identical via `readFile`.
 *
 * No production code is exercised beyond the existing engine I/O.
 */
class ImportRoundTripTest {

    private fun openVolume(device: InMemoryBlockDevice): Fat12Volume =
        Fat12Volume(device).apply { open() }

    @Test
    fun roundTrip_byteIdentical() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        // A payload that spans multiple 4 KB clusters (5000 B > one 4096 B cluster),
        // deterministic so failures are reproducible.
        val payload = Random(0xC0FFEE).nextBytes(5000)

        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/IMPORTED.BIN", sequenceOf(payload)),
        )

        val read = vol.readFile("/IMPORTED.BIN")
        assertInstanceOf(Fat12Result.Ok::class.java, read)
        val value = (read as Fat12Result.Ok).value
        assertArrayEquals(payload, value, "SC#2/SC#3: readFile bytes must equal the imported payload")
    }

    @Test
    fun roundTrip_emptyFile() {
        val device = Fat12ImageBuilder().build()
        val vol = openVolume(device)

        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/EMPTY.BIN", sequenceOf(ByteArray(0))),
        )

        val read = vol.readFile("/EMPTY.BIN")
        assertInstanceOf(Fat12Result.Ok::class.java, read)
        val value = (read as Fat12Result.Ok).value
        assertEquals(0, value.size, "a 0-byte import must round-trip to a 0-length ByteArray")
    }
}
