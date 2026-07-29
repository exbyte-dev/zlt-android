package dev.zlt.companion.data

import dev.zlt.companion.net.NetworkMode
import kotlin.math.roundToInt

/**
 * How the router describes its data connection.
 *
 * Only `ppp_connected` is treated as connected, because that is the only value
 * observed on the real device — it is the one that appears in the Python
 * project's fixtures. Other firmware states certainly exist, but guessing what
 * they are called and what they mean would be inventing protocol, so anything
 * else is surfaced verbatim rather than interpreted.
 */
sealed interface ConnectionState {
    data object Connected : ConnectionState
    data object Unknown : ConnectionState

    /** The router said something we have not seen before. Shown as-is. */
    data class Reported(val raw: String) : ConnectionState

    companion object {
        const val PPP_CONNECTED = "ppp_connected"

        fun from(raw: String): ConnectionState = when {
            raw == PPP_CONNECTED -> Connected
            raw.isBlank() -> Unknown
            else -> Reported(raw)
        }
    }
}

/** One poll's worth of router state. */
data class RouterStatus(
    val authed: Boolean,
    /** Why the authenticated readings are missing, when they are. */
    val note: String?,
    val networkType: String,
    val reading: SignalReading?,
    val rsrpDbm: Int?,
    val rssiDbm: Int?,
    /** The device's own 0-5 bar count, kept as a secondary reading only. */
    val deviceBars: String,
    val band: String,
    val rsrq: String,
    val snr: String,
    val pci: String,
    val connection: ConnectionState,
) {
    companion object {
        fun from(data: Map<String, String>, authed: Boolean, note: String?): RouterStatus {
            val rsrp = data.dbm("lte_rsrp")
            val rssi = data.dbm("rssi")
            return RouterStatus(
                authed = authed,
                note = note,
                networkType = data["network_type"].orEmpty(),
                reading = SignalReading.resolve(rsrp, rssi),
                rsrpDbm = rsrp,
                rssiDbm = rssi,
                deviceBars = data["signalbar"].orEmpty(),
                band = data["lte_band"].orEmpty(),
                rsrq = data["lte_rsrq"].orEmpty(),
                snr = data["lte_snr"].orEmpty(),
                pci = data["lte_pci"].orEmpty(),
                connection = ConnectionState.from(data["ppp_status"].orEmpty()),
            )
        }

        /**
         * A dBm reading, or null when the device left the key empty — which it
         * does routinely for the authenticated keys before login, and for all of
         * them when there is no service. Tolerates a decimal value in case some
         * firmware reports one.
         */
        private fun Map<String, String>.dbm(key: String): Int? {
            val raw = this[key]?.trim().orEmpty()
            if (raw.isEmpty()) return null
            return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.roundToInt()
        }
    }
}

/**
 * The configured network mode and what the device is actually on.
 *
 * `net_select` is checked first because it is the key that actually holds the
 * preference on this hardware; the other two are fallbacks for other firmware,
 * in the Python's order. Ported from `_resolve_configured` in zlt/web.py.
 */
data class NetModeState(
    val configured: NetworkMode?,
    val configuredRaw: String,
    val currentNetwork: String,
) {
    companion object {
        fun from(data: Map<String, String>): NetModeState {
            val raw = listOf("net_select", "net_select_mode", "m_netselect_save")
                .firstNotNullOfOrNull { key -> data[key]?.takeIf { it.isNotEmpty() } }
                .orEmpty()
            return NetModeState(
                configured = NetworkMode.fromValue(raw),
                configuredRaw = raw,
                currentNetwork = data["current_network_mode"].orEmpty(),
            )
        }
    }
}
