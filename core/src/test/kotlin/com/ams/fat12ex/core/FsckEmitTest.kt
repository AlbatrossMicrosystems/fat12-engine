package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Emits engine-written FAT12 images to `build/fsck/` for the external dosfstools
 * `fsck.fat -n` oracle (CI). Tagged `@Tag("fsck")` so it is EXCLUDED from the default
 * `:core:test` suite (it writes to build/ and its value is realised only when CI runs
 * `fsck.fat -n` on the output) — a reviewer without dosfstools still gets a clean
 * `./gradlew :core:test`. Invoke explicitly via `./gradlew :core:fsckEmit`.
 *
 * It deliberately does NOT load the committed golden fixtures: the oracle validates OUR
 * writes against an independent tool, not a byte-comparison to mkfs.fat output (which
 * would be circular).
 */
@Tag("fsck")
class FsckEmitTest {

    /** Format a fresh 1.44MB volume, apply [build], then dump the raw image to build/fsck/<name>. */
    private fun emit(name: String, build: (Fat12Volume) -> Unit) {
        val device = InMemoryBlockDevice(blockSize = 512, blocks = 2880L) // 1.44MB = 2880*512
        val vol = Fat12Volume(device)
        assertInstanceOf(Fat12Result.Ok::class.java, vol.format("FSCK"))
        assertInstanceOf(Fat12Result.Ok::class.java, vol.open())
        build(vol)
        vol.close()
        val out = Path.of("build", "fsck", name)
        Files.createDirectories(out.parent)
        Files.write(out, device.bytes)
    }

    @Test
    fun emitFreshFormatted() = emit("fresh.img") { /* no ops — just a fresh format */ }

    @Test
    fun emitAfterWriteMkdirDelete() = emit("ops.img") { vol ->
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/HELLO.TXT", sequenceOf("hello fat12".toByteArray())),
        )
        assertInstanceOf(Fat12Result.Ok::class.java, vol.mkdir("/SUB"))
        assertInstanceOf(
            Fat12Result.Ok::class.java,
            vol.writeFile("/SUB/A.TXT", sequenceOf(ByteArray(2048))),
        )
        // Delete frees clusters — exercises the free-cluster path (D-05 image #2).
        assertInstanceOf(Fat12Result.Ok::class.java, vol.delete("/HELLO.TXT"))
    }
}
