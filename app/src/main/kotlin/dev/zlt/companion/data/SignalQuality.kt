package dev.zlt.companion.data

/**
 * Signal quality, ported from the METRIC table and quality() in the Python
 * project's zlt/static/js/signal.js.
 *
 * Two metrics, because the good one needs a session: RSRP is only populated
 * once logged in, so RSSI is the fallback. RSRP is preferred whenever it is
 * available, exactly as the web dashboard does it.
 */
enum class SignalMetric(
    val label: String,
    val floor: Int,
    val ceiling: Int,
    private val fair: Int,
    private val good: Int,
    private val excellent: Int,
) {
    RSRP("RSRP", floor = -125, ceiling = -70, fair = -100, good = -90, excellent = -80),
    RSSI("RSSI", floor = -100, ceiling = -45, fair = -75, good = -65, excellent = -55);

    fun levelFor(dbm: Int): SignalLevel = when {
        dbm >= excellent -> SignalLevel.EXCELLENT
        dbm >= good -> SignalLevel.GOOD
        dbm >= fair -> SignalLevel.FAIR
        else -> SignalLevel.WEAK
    }

    /** Position on the metric's own scale, 0f..1f, for the meter fill. */
    fun fractionFor(dbm: Int): Float =
        ((dbm.coerceIn(floor, ceiling) - floor).toFloat() / (ceiling - floor).toFloat())
}

/**
 * The four quality tiers, and the number of bars each one draws.
 *
 * Bars are derived from the tier rather than read from the device's own
 * `signalbar` key. The two can disagree — `signalbar` is the router's own 0-5
 * scale and does not use these thresholds — and a display showing five bars
 * next to the word "Weak" is worse than useless. Deriving both from one number
 * means they can never contradict each other. The device's `signalbar` is still
 * shown, as one of the secondary readings.
 */
enum class SignalLevel(val bars: Int) {
    WEAK(1),
    FAIR(2),
    GOOD(3),
    EXCELLENT(4);

    companion object {
        const val MAX_BARS = 4
    }
}

/** One resolved reading: which metric it came from, the raw dBm, and its tier. */
data class SignalReading(
    val metric: SignalMetric,
    val dbm: Int,
    val level: SignalLevel,
) {
    val bars: Int get() = level.bars
    val fraction: Float get() = metric.fractionFor(dbm)

    companion object {
        /**
         * Resolves a reading from the two dBm values, preferring RSRP.
         * Returns null when the device reported neither, which happens when it
         * has no service at all.
         */
        fun resolve(rsrpDbm: Int?, rssiDbm: Int?): SignalReading? {
            val metric = when {
                rsrpDbm != null -> SignalMetric.RSRP
                rssiDbm != null -> SignalMetric.RSSI
                else -> return null
            }
            val dbm = rsrpDbm ?: rssiDbm!!
            return SignalReading(metric, dbm, metric.levelFor(dbm))
        }
    }
}
