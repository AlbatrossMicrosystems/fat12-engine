package com.ams.fat12ex.core.testutil

/**
 * Classloader loader for the committed `mkfs.fat` golden fixtures.
 *
 * The three `.img` files under `core/src/testFixtures/resources/golden/` are reference
 * FAT12 images generated once with dosfstools 4.2 (`mkfs.fat -F 12`), verified
 * `fsck.fat -n`-clean, and committed as read-only test inputs. They are loaded
 * here byte-for-byte for cross-validation of [com.ams.fat12ex.core.Fat12Formatter].
 * Generation provenance (exact commands + SOURCE_DATE_EPOCH) is documented in
 * `testdata/README.md`.
 */
object GoldenImages {
    fun load1440k(): ByteArray =
        javaClass.getResourceAsStream("/golden/fat12_1440k_empty.img")!!.readBytes()

    fun load720k(): ByteArray =
        javaClass.getResourceAsStream("/golden/fat12_720k_empty.img")!!.readBytes()

    fun load360k(): ByteArray =
        javaClass.getResourceAsStream("/golden/fat12_360k_empty.img")!!.readBytes()
}
