package io.github.xororz.localdream.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoVideoTimelineTest {
    @Test fun completeDurationMatchesUserSelection() {
        assertEquals(48, PhotoVideoTimeline.totalFrames(2, 24, 1))
        assertEquals(450, PhotoVideoTimeline.totalFrames(5, 30, 3))
    }

    @Test fun firstFrameIsFirstPhotoOnly() {
        val first = PhotoVideoTimeline.frameAt(0, 3, 24, 2)
        assertEquals(0, first.index)
        assertEquals(null, first.nextIndex)
        assertEquals(0f, first.opacity)
    }

    @Test fun crossfadeCoversEndOfPhotoInterval() {
        val before = PhotoVideoTimeline.frameAt(39, 3, 24, 2)
        val fade = PhotoVideoTimeline.frameAt(40, 3, 24, 2)
        val end = PhotoVideoTimeline.frameAt(47, 3, 24, 2)
        assertEquals(null, before.nextIndex)
        assertEquals(1, fade.nextIndex)
        assertTrue(fade.opacity in 0f..1f)
        assertEquals(1f, end.opacity)
        val next = PhotoVideoTimeline.frameAt(48, 3, 24, 2)
        assertEquals(1, next.index)
        assertEquals(null, next.nextIndex)
    }

    @Test fun finalPhotoDoesNotFadeToAnAbsentImage() {
        val last = PhotoVideoTimeline.frameAt(95, 2, 24, 2)
        assertEquals(1, last.index)
        assertEquals(null, last.nextIndex)
        assertEquals(0f, last.opacity)
    }

    @Test fun lowFpsBoundaryIsSafe() {
        val frame = PhotoVideoTimeline.frameAt(0, 2, 1, 1)
        assertEquals(0, frame.index)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnboundedPhotoCounts() {
        PhotoVideoTimeline.totalFrames(200, 60, 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesNegativeFrameIndex() {
        PhotoVideoTimeline.frameAt(-1, 2, 24, 1)
    }
}
