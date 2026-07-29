package dev.zlt.companion.data

import dev.zlt.companion.net.NetworkMode
import dev.zlt.companion.net.RouterMock
import dev.zlt.companion.net.ZltClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterRepositoryTest {

    private val router = RouterMock()

    private fun repository(password: String = "admin") = RouterRepository(
        credentialsProvider = { router.credentials(password = password) },
        clientFactory = { ZltClient(it, timeoutSeconds = 5) },
    )

    /** Answers as a logged-in device on a healthy LTE connection. */
    private fun healthyDevice() {
        router.values += mapOf(
            "token" to "1",
            "network_type" to "LTE",
            "rssi" to "-70",
            "signalbar" to "5",
            "lte_rsrq" to "-11",
            "lte_pci" to "244",
            "ppp_status" to "ppp_connected",
            "lte_rsrp" to "-85",
            "lte_band" to "3",
            "lte_snr" to "12",
            "net_select" to "Only_LTE",
            "current_network_mode" to "LTE",
        )
    }

    @After
    fun tearDown() = router.close()

    @Test
    fun `status and mode come back in a single read`() = runTest {
        healthyDevice()

        val snapshot = repository().snapshot()

        assertEquals(SignalMetric.RSRP, snapshot.status.reading!!.metric)
        assertEquals(NetworkMode.LTE_ONLY, snapshot.mode!!.configured)
        assertEquals("LTE", snapshot.mode!!.currentNetwork)

        // One token check plus one combined read. The whole point: the mode is
        // not costing a second round trip, so it can be refreshed every poll.
        assertEquals(2, router.requestsSeen.size)
        val path = router.requestsSeen.last().path!!
        assertTrue(path.contains("net_select"))
        assertTrue(path.contains("lte_rsrp"))
        assertTrue(path.contains("multi_data=1"))
    }

    @Test
    fun `a mode changed elsewhere is picked up on the next poll`() = runTest {
        // The bug this replaced: the mode was read once and never again, so a
        // change made from the router's own web UI never appeared.
        healthyDevice()
        val repository = repository()
        assertEquals(NetworkMode.LTE_ONLY, repository.snapshot().mode!!.configured)

        router.values["net_select"] = "Only_GSM"

        assertEquals(NetworkMode.GSM_ONLY, repository.snapshot().mode!!.configured)
    }

    @Test
    fun `falls back to a separate read when the device drops the mode keys`() = runTest {
        healthyDevice()
        // Firmware that will not serve this many cmds at once: the status keys
        // come back, the mode keys are absent from the response entirely.
        router.answerAtMostKeys(ZltProtocolKeyCount.STATUS_KEYS)

        val snapshot = repository().snapshot()

        assertNotNull("expected the fallback read to recover the mode", snapshot.mode)
        assertEquals(NetworkMode.LTE_ONLY, snapshot.mode!!.configured)
        // token, combined read, then the extra mode read.
        assertEquals(3, router.requestsSeen.size)
    }

    @Test
    fun `an empty mode key is an answer and does not trigger a second read`() = runTest {
        // Present-but-empty means "nothing configured", which is a real answer.
        // Retrying it every poll would double the traffic for no reason.
        healthyDevice()
        router.values += mapOf(
            "net_select" to "", "net_select_mode" to "", "m_netselect_save" to "",
        )

        val snapshot = repository().snapshot()

        assertNull(snapshot.mode!!.configured)
        assertEquals(2, router.requestsSeen.size)
    }

    @Test
    fun `login failure degrades to the open keys with no mode`() = runTest {
        // No password: login cannot even be attempted, so the open keys are all
        // that is readable and the mode is unreadable rather than stale.
        router.values += mapOf(
            "get_token" to "",
            "network_type" to "LTE",
            "rssi" to "-70",
            "ppp_status" to "ppp_connected",
        )

        val snapshot = repository(password = "").snapshot()

        assertEquals(false, snapshot.status.authed)
        assertEquals(SignalMetric.RSSI, snapshot.status.reading!!.metric)
        assertNull(snapshot.mode)
        assertNotNull(snapshot.status.note)
    }

    @Test
    fun `no service suppresses the reading but keeps the raw figures`() = runTest {
        // Captured live: with no network the device still echoes the last rssi
        // and signalbar it saw. Reporting "Weak" off those would claim a poor
        // connection where there is none.
        healthyDevice()
        router.values += mapOf(
            "network_type" to "No Service",
            "rssi" to "-91",
            "signalbar" to "4",
            "lte_rsrp" to "",
            "ppp_status" to "ppp_disconnected",
        )

        val status = repository().snapshot().status

        assertNull(status.reading)
        assertEquals(false, status.hasService)
        assertEquals(ConnectionState.Disconnected, status.connection)
        // Still available to anyone who opens Details.
        assertEquals(-91, status.rssiDbm)
        assertEquals("4", status.deviceBars)
    }

    @Test
    fun `setMode writes then reports what the router actually applied`() = runTest {
        healthyDevice()
        router.queuePost("success")
        // The device applies 3G rather than the 4G that was asked for.
        router.values["net_select"] = "TD_W"

        val applied = repository().setMode(NetworkMode.LTE_ONLY)

        assertTrue(router.postsSeen.first().contains("BearerPreference=Only_LTE"))
        assertEquals(NetworkMode.WCDMA_ONLY, applied.configured)
    }

    @Test
    fun `setMode raises when the router refuses`() = runTest {
        healthyDevice()
        router.queuePost("failure")

        val error = runCatching { repository().setMode(NetworkMode.GSM_ONLY) }.exceptionOrNull()
        assertNotNull(error)
    }
}

/** Spelled out rather than imported, for the same reason RouterMock is. */
private object ZltProtocolKeyCount {
    /** network_type, rssi, signalbar, lte_rsrq, lte_pci, ppp_status + the 3 authed. */
    const val STATUS_KEYS = 9
}
