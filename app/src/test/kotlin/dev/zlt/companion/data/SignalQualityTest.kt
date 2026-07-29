package dev.zlt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thresholds ported from zlt/static/js/signal.js, boundaries included. */
class SignalQualityTest {

    @Test
    fun `rsrp thresholds match the dashboard`() {
        val m = SignalMetric.RSRP
        assertEquals(SignalLevel.EXCELLENT, m.levelFor(-80))   // boundary, inclusive
        assertEquals(SignalLevel.EXCELLENT, m.levelFor(-75))
        assertEquals(SignalLevel.GOOD, m.levelFor(-81))
        assertEquals(SignalLevel.GOOD, m.levelFor(-90))        // boundary, inclusive
        assertEquals(SignalLevel.FAIR, m.levelFor(-91))
        assertEquals(SignalLevel.FAIR, m.levelFor(-100))       // boundary, inclusive
        assertEquals(SignalLevel.WEAK, m.levelFor(-101))
        assertEquals(SignalLevel.WEAK, m.levelFor(-125))
    }

    @Test
    fun `rssi thresholds match the dashboard`() {
        val m = SignalMetric.RSSI
        assertEquals(SignalLevel.EXCELLENT, m.levelFor(-55))
        assertEquals(SignalLevel.GOOD, m.levelFor(-56))
        assertEquals(SignalLevel.GOOD, m.levelFor(-65))
        assertEquals(SignalLevel.FAIR, m.levelFor(-66))
        assertEquals(SignalLevel.FAIR, m.levelFor(-75))
        assertEquals(SignalLevel.WEAK, m.levelFor(-76))
    }

    @Test
    fun `rsrp is preferred when both are present`() {
        // -95 is FAIR on the RSRP scale; -50 would be EXCELLENT on the RSSI one.
        val reading = SignalReading.resolve(rsrpDbm = -95, rssiDbm = -50)!!
        assertEquals(SignalMetric.RSRP, reading.metric)
        assertEquals(-95, reading.dbm)
        assertEquals(SignalLevel.FAIR, reading.level)
    }

    @Test
    fun `rssi is used when rsrp is absent`() {
        // Which is what happens whenever login failed: RSRP needs a session.
        val reading = SignalReading.resolve(rsrpDbm = null, rssiDbm = -60)!!
        assertEquals(SignalMetric.RSSI, reading.metric)
        assertEquals(SignalLevel.GOOD, reading.level)
    }

    @Test
    fun `no reading at all resolves to null`() {
        assertNull(SignalReading.resolve(null, null))
    }

    @Test
    fun `bars and word can never disagree`() {
        // The property that motivated deriving bars from the tier rather than
        // reading the device's own signalbar.
        for (dbm in -140..-30) {
            val reading = SignalReading.resolve(dbm, null)!!
            assertEquals(reading.level.bars, reading.bars)
            assertTrue(reading.bars in 1..SignalLevel.MAX_BARS)
        }
    }

    @Test
    fun `fraction is clamped to the metric scale`() {
        assertEquals(0f, SignalMetric.RSRP.fractionFor(-200), 0.0001f)
        assertEquals(1f, SignalMetric.RSRP.fractionFor(0), 0.0001f)
        assertEquals(0.5f, SignalMetric.RSSI.fractionFor((-100 + -45) / 2), 0.02f)
    }
}
