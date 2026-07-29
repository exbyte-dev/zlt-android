package dev.zlt.companion.data

import dev.zlt.companion.net.NetworkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetModeStateTest {

    @Test
    fun `net_select wins over the other keys`() {
        // The whole point of the ordering: on this hardware net_select is the
        // populated key, and the Python's notes record that net_select_mode and
        // m_netselect_save are not reliable here.
        val state = NetModeState.from(
            mapOf(
                "net_select" to "Only_LTE",
                "net_select_mode" to "NETWORK_auto",
                "m_netselect_save" to "TD_W",
                "current_network_mode" to "LTE",
            ),
        )
        assertEquals(NetworkMode.LTE_ONLY, state.configured)
        assertEquals("LTE", state.currentNetwork)
    }

    @Test
    fun `falls back through the other keys in order`() {
        assertEquals(
            NetworkMode.AUTO,
            NetModeState.from(
                mapOf("net_select" to "", "net_select_mode" to "NETWORK_auto"),
            ).configured,
        )
        assertEquals(
            NetworkMode.GSM_ONLY,
            NetModeState.from(
                mapOf("net_select" to "", "net_select_mode" to "", "m_netselect_save" to "Only_GSM"),
            ).configured,
        )
    }

    @Test
    fun `all keys empty resolves to nothing configured`() {
        val state = NetModeState.from(
            mapOf("net_select" to "", "net_select_mode" to "", "m_netselect_save" to ""),
        )
        assertNull(state.configured)
        assertEquals("", state.configuredRaw)
    }

    @Test
    fun `an unrecognised value is kept raw rather than dropped`() {
        // Other firmware could report a BearerPreference this app does not know.
        // Showing what the router said beats showing nothing.
        val state = NetModeState.from(mapOf("net_select" to "Only_WCDMA"))
        assertNull(state.configured)
        assertEquals("Only_WCDMA", state.configuredRaw)
    }

    @Test
    fun `three g maps to TD_W not Only_WCDMA`() {
        // Called out as a live-verified correction in the Python's protocol notes.
        assertEquals("TD_W", NetworkMode.WCDMA_ONLY.value)
        assertEquals(NetworkMode.WCDMA_ONLY, NetworkMode.fromValue("TD_W"))
        assertNull(NetworkMode.fromValue("Only_WCDMA"))
    }
}

class RouterStatusTest {

    @Test
    fun `reads a full authenticated poll`() {
        val status = RouterStatus.from(
            mapOf(
                "network_type" to "LTE",
                "rssi" to "-70",
                "signalbar" to "5",
                "lte_rsrq" to "-11",
                "lte_pci" to "244",
                "ppp_status" to "ppp_connected",
                "lte_rsrp" to "-85",
                "lte_band" to "3",
                "lte_snr" to "12",
            ),
            authed = true,
            note = null,
        )

        assertEquals("LTE", status.networkType)
        assertEquals(-85, status.rsrpDbm)
        assertEquals(-70, status.rssiDbm)
        assertEquals(SignalMetric.RSRP, status.reading!!.metric)
        assertEquals(SignalLevel.GOOD, status.reading!!.level)
        assertEquals(ConnectionState.Connected, status.connection)
        assertEquals("5", status.deviceBars)
    }

    @Test
    fun `empty authed keys leave rsrp null and fall back to rssi`() {
        // Exactly the shape of an open-keys-only poll: the device answers with
        // the key present but empty rather than omitting it.
        val status = RouterStatus.from(
            mapOf("rssi" to "-70", "lte_rsrp" to "", "lte_band" to "", "lte_snr" to ""),
            authed = false,
            note = "not logged in",
        )

        assertNull(status.rsrpDbm)
        assertEquals(SignalMetric.RSSI, status.reading!!.metric)
        assertEquals(-70, status.reading!!.dbm)
    }

    @Test
    fun `no signal at all leaves the reading null`() {
        val status = RouterStatus.from(
            mapOf("rssi" to "", "lte_rsrp" to "", "network_type" to ""),
            authed = true,
            note = null,
        )
        assertNull(status.reading)
    }

    @Test
    fun `the three captured ppp states are named`() {
        // All three observed on the live device: connected, mid-reattach after a
        // mode change, and with the connection dropped.
        assertEquals(ConnectionState.Connected, ConnectionState.from("ppp_connected"))
        assertEquals(ConnectionState.Connecting, ConnectionState.from("ppp_connecting"))
        assertEquals(ConnectionState.Disconnected, ConnectionState.from("ppp_disconnected"))
        assertEquals(ConnectionState.Unknown, ConnectionState.from(""))
    }

    @Test
    fun `an uncaptured ppp state is carried through rather than guessed at`() {
        // ppp_disconnecting probably exists, but there is no capture of it, so
        // it must surface as itself rather than be mapped on a hunch.
        assertEquals(
            ConnectionState.Reported("ppp_disconnecting"),
            ConnectionState.from("ppp_disconnecting"),
        )
    }

    @Test
    fun `a decimal dbm reading is tolerated`() {
        val status = RouterStatus.from(mapOf("rssi" to "-70.4"), authed = false, note = null)
        assertEquals(-70, status.rssiDbm)
    }
}
