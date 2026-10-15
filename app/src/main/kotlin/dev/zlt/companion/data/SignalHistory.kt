package dev.zlt.companion.data

/**
 * The walk-test trace: a rolling window of readings, ported from pushHist in
 * the web dashboard's signal.js. Carry the router around and watch the line
 * respond.
 */
data class SignalSample(val atEpochMs: Long, val rsrpDbm: Int?, val rssiDbm: Int?)

object SignalHistory {

    /** Fifteen minutes, as in the dashboard. */
    const val WINDOW_MS = 15 * 60 * 1000L

    /**
     * The y-axis the trace is drawn against. Both metrics share one axis so the
     * two lines can be compared; the range is the dashboard's.
     */
    const val AXIS_MIN_DBM = -120
    const val AXIS_MAX_DBM = -45

    /** Appends [sample] and drops whatever has fallen out of the window. */
    fun append(history: List<SignalSample>, sample: SignalSample): List<SignalSample> =
        (history + sample).dropWhile { sample.atEpochMs - it.atEpochMs > WINDOW_MS }

    /** 0f at the bottom of the axis, 1f at the top. */
    fun heightFraction(dbm: Int): Float =
        (dbm.coerceIn(AXIS_MIN_DBM, AXIS_MAX_DBM) - AXIS_MIN_DBM).toFloat() /
            (AXIS_MAX_DBM - AXIS_MIN_DBM).toFloat()
}
