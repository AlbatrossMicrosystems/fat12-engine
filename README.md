# fat12-engine

[![CI](https://github.com/AlbatrossMicrosystems/fat12-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/AlbatrossMicrosystems/fat12-engine/actions/workflows/ci.yml)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20633046.svg)](https://doi.org/10.5281/zenodo.20633046)

A dependency-free, pure-JVM **FAT12 write layer** with an atomic
**verify-after-write + rollback** contract. Every multi-step mutation (file write,
`mkdir`, `rename`, recursive `delete`, set-label, set-attributes) writes through a
per-operation undo log: each touched sector is captured before it is written, every
data cluster is read back and byte-compared after it is written, and on **any** failure
(write error, verify mismatch, or disk-full) the whole operation is rolled back to a
byte-for-byte identical pre-operation state.

This repository is the engine `:core` module only. To our knowledge it is an unusually
small, self-contained implementation of verified, rollback-safe FAT12 writes; we make no
priority claim. An Android application that drives this engine over USB-OTG storage is a
separate demonstrator and is **not** part of this repository.

## Requirements

- JDK 17 (the Gradle `foojay-resolver` toolchain plugin auto-provisions a matching JDK 17
  if one is not already installed).
- No Android SDK, no device, no network at test time.

## Build and test

```bash
./gradlew :core:test
```

This compiles the engine and runs the full headless test suite on JDK 17. There is no
Android dependency and no hardware is required.

## Usage example

The engine works against a single storage seam, `BlockDevice` (partition-relative byte
offsets). Below it is driven against a trivial in-memory backing buffer; in the
demonstrator app the same interface is implemented over USB-OTG storage.

```kotlin
import com.ams.fat12ex.core.BlockDevice
import com.ams.fat12ex.core.Fat12Result
import com.ams.fat12ex.core.Fat12Volume
import java.nio.ByteBuffer

// A minimal in-memory BlockDevice (1.44 MB floppy geometry: 2880 * 512-byte sectors).
class MemoryBlockDevice(override val blocks: Long = 2880) : BlockDevice {
    override val blockSize: Int = 512
    private val store = ByteArray((blocks * blockSize).toInt())
    override fun init() {}
    override fun read(byteOffset: Long, dest: ByteBuffer) {
        val n = dest.remaining()
        dest.put(store, byteOffset.toInt(), n)
    }
    override fun write(byteOffset: Long, src: ByteBuffer) {
        val n = src.remaining()
        src.get(store, byteOffset.toInt(), n)
    }
}

fun main() {
    val device = MemoryBlockDevice().also { it.init() }
    val volume = Fat12Volume(device)

    // Format a fresh FAT12 volume, then open it.
    volume.format("DEMO")
    check(volume.open() is Fat12Result.Ok)

    // Atomic, verify-after-write streaming write of a file into the root directory.
    val payload = "hello fat12".toByteArray()
    val write = volume.writeFile("/HELLO.TXT", sequenceOf(payload))
    check(write is Fat12Result.Ok)

    // List the root directory.
    val listing = volume.list("/")
    if (listing is Fat12Result.Ok) {
        listing.value.forEach { println("${it.name}  ${it.size} bytes") }
    }

    // Read the file back.
    val read = volume.readFile("/HELLO.TXT")
    if (read is Fat12Result.Ok) {
        println(String(read.value))   // -> hello fat12
    }

    volume.close()
}
```

All write operations return a sealed `Fat12Result` (`Ok`, `NotFound`, `NameConflict`,
`DiskFull`, `InvalidName`, ...); unrecoverable I/O or verify faults roll back and surface
as exceptions after the volume has been restored to its pre-operation bytes.

## Claims and the tests that substantiate them

The engine makes three substantiable correctness claims. Each maps to specific `:core`
test files:

| Claim | What it asserts | Tests |
|-------|-----------------|-------|
| C-A | crash-consistency / interrupted-write rollback (a fault mid-write leaves the volume byte-for-byte at its pre-op state) | `Fat12VolumeOpsTest.kt`, `UndoLogTest.kt`, `BlockDeviceIoAlignmentTest.kt` |
| C-B | byte-for-byte rollback of in-place metadata edits (label / attributes / round-trip import) | `SetVolumeLabelTest.kt`, `SetAttributesTest.kt`, `ImportRoundTripTest.kt`, `SetVolumeLabel4KnTest.kt` |
| C-C | `mkfs.fat` golden conformance (formatter output matches reference golden images) | the `Fat12Formatter` tests + `GoldenImages.kt` (test fixtures) cross-validating `core/src/testFixtures/resources/golden/*.img` |

The committed golden images live at `core/src/testFixtures/resources/golden/*.img`
(`fat12_1440k_empty.img`, `fat12_720k_empty.img`, `fat12_360k_empty.img`).

The full suite is **2119 tests** (0 failures), run headless on a clean JDK 17 with
`./gradlew :core:test` — the figure that command reports (it excludes the two `@Tag("fsck")`
image-emit tests, which exist only to feed the external `fsck.fat` oracle, not to assert
engine behaviour). Continuous integration (GitHub Actions, pinned Temurin 17 + an
independent dosfstools `fsck.fat -n` oracle) runs this suite on every push; the status badge
above reflects the latest run.

Golden generation is documented and version-pinned in [`testdata/README.md`](testdata/README.md)
(dosfstools 4.2, exact `mkfs.fat` command lines, `SOURCE_DATE_EPOCH` for byte-reproducible
regeneration).

## License

Apache License 2.0 — see [LICENSE](LICENSE), [NOTICE](NOTICE),
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), and [PROVENANCE.md](PROVENANCE.md).
