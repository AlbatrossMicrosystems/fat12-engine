package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Executes the README "Usage example" round-trip against the build so the
 * documented snippet cannot silently bit-rot as the public API evolves.
 *
 * Mirrors the README flow: format -> open -> writeFile("/HELLO.TXT") ->
 * list("/") -> readFile("/HELLO.TXT"), asserting the round-tripped bytes equal
 * "hello fat12". Uses the in-memory device fixture at the README's 1.44 MB
 * floppy geometry (2880 * 512-byte sectors) in place of the snippet's
 * hand-written MemoryBlockDevice.
 */
class ReadmeExampleTest {

    @Test
    fun readmeUsageExample_roundTrips() {
        // A minimal in-memory BlockDevice at 1.44 MB floppy geometry.
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L).also { it.init() }
        val volume = Fat12Volume(device)

        // Format a fresh FAT12 volume, then open it.
        volume.format("DEMO")
        assertInstanceOf(Fat12Result.Ok::class.java, volume.open())

        // Atomic, verify-after-write streaming write of a file into the root directory.
        val payload = "hello fat12".toByteArray()
        val write = volume.writeFile("/HELLO.TXT", sequenceOf(payload))
        assertInstanceOf(Fat12Result.Ok::class.java, write)

        // List the root directory — HELLO.TXT must be present at the written size.
        val listing = volume.list("/")
        assertInstanceOf(Fat12Result.Ok::class.java, listing)
        val entry = (listing as Fat12Result.Ok).value
            .firstOrNull { it.name.equals("HELLO.TXT", ignoreCase = true) }
        assertTrue(entry != null, "HELLO.TXT must appear in the root listing")
        assertEquals(payload.size.toLong(), entry!!.size)

        // Read the file back — the raw bytes must round-trip exactly (the README's
        // String(read.value) decode is the human-facing view of that same payload).
        val read = volume.readFile("/HELLO.TXT")
        assertInstanceOf(Fat12Result.Ok::class.java, read)
        val readBytes = (read as Fat12Result.Ok).value
        assertArrayEquals(payload, readBytes)
        assertEquals("hello fat12", String(readBytes))

        volume.close()
    }
}
