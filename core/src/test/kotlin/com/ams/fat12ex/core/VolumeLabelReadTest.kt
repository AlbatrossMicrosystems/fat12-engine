package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.Fat12ImageBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression for the Radix volume-label READ bug: the device's real label
 * "RADIX 2 HD" lives in the root-directory `ATTR_VOLUME_ID` entry, while the
 * boot-sector `BS_VolLab` copy at 0x2B is "NO NAME". [Fat12Volume.volumeLabel]
 * used to read only `BS_VolLab`, mislabelling the volume as "NO NAME".
 */
class VolumeLabelReadTest {

    @Test
    fun volumeLabel_prefersRootVolumeIdEntry_overBootSectorNoName() {
        val device = Fat12ImageBuilder()
            .withReservedShortEntry(name83 = "RADIX 2 HD ", attr = 0x08) // ATTR_VOLUME_ID
            .withReservedShortEntry(
                name83 = "README  HTM",
                attr = 0x20,
                clusters = listOf(2),
                bytes = "hi".toByteArray(Charsets.US_ASCII),
            )
            .build()
        // BS_VolLab @0x2B = "NO NAME    " — what the old reader returned.
        "NO NAME    ".toByteArray(Charsets.US_ASCII).copyInto(device.bytes, 0x2B)

        val vol = Fat12Volume(device).apply { open() }
        assertEquals("RADIX 2 HD", vol.volumeLabel())
    }

    @Test
    fun volumeLabel_fallsBackToBootSector_whenNoVolumeIdEntry() {
        val device = Fat12ImageBuilder()
            .withReservedShortEntry(
                name83 = "README  HTM",
                attr = 0x20,
                clusters = listOf(2),
                bytes = "hi".toByteArray(Charsets.US_ASCII),
            )
            .build()
        "MYLABEL    ".toByteArray(Charsets.US_ASCII).copyInto(device.bytes, 0x2B)

        val vol = Fat12Volume(device).apply { open() }
        assertEquals("MYLABEL", vol.volumeLabel())
    }
}
