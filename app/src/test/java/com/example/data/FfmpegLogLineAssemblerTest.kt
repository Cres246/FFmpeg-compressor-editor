package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FfmpegLogLineAssemblerTest {

    @Test
    fun reassemblesFragmentedAvLogChunksIntoSingleLogicalLine() {
        val assembler = FfmpegLogLineAssembler()

        assertEquals(emptyList<String>(), assembler.append("  Stream #0"))
        assertEquals(emptyList<String>(), assembler.append(":0"))
        assertEquals(emptyList<String>(), assembler.append("(eng)"))
        assertEquals(
            listOf("  Stream #0:0(eng): Video: vp9 (Profile 0), yuv420p(tv), 1920x1080"),
            assembler.append(": Video: vp9 (Profile 0), yuv420p(tv), 1920x1080\n")
        )
        assertNull(assembler.flush())
    }

    @Test
    fun handlesMultiLineChunkAndTrailingPartialFlush() {
        val assembler = FfmpegLogLineAssembler()

        val firstBatch = assembler.append("bench: utime=6.538s stime=0.823s rtime=1.878s\r\nbench: maxrss=92332KiB")
        assertEquals(listOf("bench: utime=6.538s stime=0.823s rtime=1.878s"), firstBatch)
        assertEquals("bench: maxrss=92332KiB", assembler.flush())
        assertNull(assembler.flush())
    }

    @Test
    fun dropsStandaloneCarriageReturnProgressOverwrites() {
        val assembler = FfmpegLogLineAssembler()

        assertEquals(emptyList<String>(), assembler.append("frame=  120 fps=390 q=-0.0 size=N/A time=00:00:04.00\r"))
        assertEquals(emptyList<String>(), assembler.append("size=   4096kB time=00:00:08.00 bitrate=4194.3kbits/s\r"))
        assertEquals(
            listOf("video:311KiB audio:0KiB subtitle:0KiB"),
            assembler.append("video:311KiB audio:0KiB subtitle:0KiB\n")
        )
    }

    @Test
    fun boundsSingleUnbrokenLineAtMaxLineChars() {
        val assembler = FfmpegLogLineAssembler(maxLineChars = 16)
        val emitted = assembler.append("0123456789ABCDEFrest\n")
        assertEquals(listOf("0123456789ABCDEF", "rest"), emitted)
    }
}
