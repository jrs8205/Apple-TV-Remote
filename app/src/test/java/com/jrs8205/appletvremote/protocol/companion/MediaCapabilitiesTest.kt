package com.jrs8205.appletvremote.protocol.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCapabilitiesTest {

    @Test
    fun pauseBitWithoutPlayBitMeansPlaying() {
        assertEquals(PlayState.PLAYING, MediaCapabilities(0x2).playState)
        assertEquals(PlayState.PLAYING, MediaCapabilities(0x2 or 0x4 or 0x8).playState)
    }

    @Test
    fun playBitWithoutPauseBitMeansPaused() {
        assertEquals(PlayState.PAUSED, MediaCapabilities(0x1).playState)
    }

    @Test
    fun noRelevantBitsMeansInactive() {
        assertEquals(PlayState.INACTIVE, MediaCapabilities(0).playState)
        assertEquals(PlayState.INACTIVE, MediaCapabilities(0x40).playState)
    }

    @Test
    fun bothPlayAndPauseBitsAreUnknown() {
        assertEquals(PlayState.UNKNOWN, MediaCapabilities(0x3).playState)
    }

    @Test
    fun capabilityFlags() {
        val caps = MediaCapabilities(0x4 or 0x200)
        assertTrue(caps.canNext)
        assertFalse(caps.canPrevious)
        assertTrue(caps.canSkipForward)
        assertFalse(caps.canSkipBackward)
        val all = MediaCapabilities(0x4 or 0x8 or 0x200 or 0x400)
        assertTrue(all.canPrevious && all.canSkipBackward)
    }

    @Test
    fun parsesFromEventContent() {
        assertEquals(MediaCapabilities(0x202), MediaCapabilities.fromEvent(mapOf("_mcF" to 0x202L)))
        assertEquals(MediaCapabilities(0), MediaCapabilities.fromEvent(emptyMap<String, Any>()))
    }
}
