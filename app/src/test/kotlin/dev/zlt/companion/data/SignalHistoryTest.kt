package dev.zlt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalHistoryTest {

    @Test
    fun `samples older than the window are dropped`() {
        val start = 1_000_000L
        var history = emptyList<SignalSample>()
        for (minute in 0..20) {
            history = SignalHistory.append(history, SignalSample(start + minute * 60_000L, -90, -60))
        }
        // Minutes 5 through 20 are within fifteen minutes of minute 20.
        assertEquals(16, history.size)
        assertEquals(start + 5 * 60_000L, history.first().atEpochMs)
    }

    @Test
    fun `gaps are kept as samples`() {
        val history = SignalHistory.append(listOf(SignalSample(0, -90, -60)), SignalSample(1, null, null))
        assertEquals(2, history.size)
    }

    @Test
    fun `height is clamped to the axis`() {
        assertEquals(0f, SignalHistory.heightFraction(-200))
        assertEquals(1f, SignalHistory.heightFraction(0))
        assertEquals(0.4f, SignalHistory.heightFraction(-90), 0.0001f)
    }
}
