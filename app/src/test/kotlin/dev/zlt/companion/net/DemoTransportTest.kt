package dev.zlt.companion.net

import dev.zlt.companion.data.RouterRepository
import dev.zlt.companion.data.SignalLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo mode has to satisfy the same contract the real client does. If it stops
 * doing so, that is a failing test here rather than a Play review rejection.
 */
class DemoTransportTest {

    private fun repository() = RouterRepository(
        credentialsProvider = { DemoTransport.CREDENTIALS },
        clientFactory = { ZltClient(it, baseClient = DemoTransport.okHttpClient()) },
    )

    @Test
    fun `snapshot returns a full authenticated reading`() = runTest {
        val snapshot = repository().snapshot()

        assertTrue("demo should log in and reach the authed keys", snapshot.status.authed)
        assertEquals("LTE", snapshot.status.networkType)
        assertTrue(snapshot.status.hasService)

        val reading = snapshot.status.reading
        assertNotNull("a demo reading should resolve", reading)
        assertEquals("RSRP", reading!!.metric.label)
        assertEquals(-78, reading.dbm)
        assertEquals(SignalLevel.EXCELLENT, reading.level)
    }

    @Test
    fun `snapshot reports the configured mode`() = runTest {
        val snapshot = repository().snapshot()
        assertEquals(NetworkMode.LTE_ONLY, snapshot.mode?.configured)
    }

    @Test
    fun `setMode changes what the next snapshot reports`() = runTest {
        val repository = repository()

        val applied = repository.setMode(NetworkMode.AUTO)
        assertEquals(NetworkMode.AUTO, applied.configured)

        assertEquals(NetworkMode.AUTO, repository.snapshot().mode?.configured)
    }

    @Test
    fun `each transport starts from the same fixture`() = runTest {
        repository().setMode(NetworkMode.GSM_ONLY)
        // A fresh transport must not inherit the previous one's mutation.
        assertEquals(NetworkMode.LTE_ONLY, repository().snapshot().mode?.configured)
    }
}
