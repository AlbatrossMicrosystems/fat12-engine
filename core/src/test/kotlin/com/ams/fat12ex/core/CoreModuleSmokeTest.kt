package com.ams.fat12ex.core

import com.ams.fat12ex.core.testutil.InMemoryBlockDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer

class CoreModuleSmokeTest {
    @Test
    fun inMemoryDevice_writeThenRead_roundTrips() {
        val dev = InMemoryBlockDevice(blockSize = 512, blocks = 16L)
        val payload = ByteArray(512) { (it and 0xFF).toByte() }
        dev.write(0L, ByteBuffer.wrap(payload))
        val back = ByteBuffer.allocate(512)
        dev.read(0L, back)
        assertEquals(payload.toList(), back.array().toList())
    }

    @Test
    fun failNextWriteAt_throwsIOExceptionOnce() {
        val dev = InMemoryBlockDevice(blockSize = 512, blocks = 16L)
        dev.failNextWriteAt(0L)
        assertThrows(IOException::class.java) { dev.write(0L, ByteBuffer.allocate(512)) }
    }
}
