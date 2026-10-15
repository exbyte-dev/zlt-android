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

    // --- SMS and USSD --------------------------------------------------------
    // Run through the same client code as a real device, so the demo exercises
    // the encodings and the status-slot polls rather than skipping them.

    @Test
    fun `inbox decodes with unread and outgoing rows`() = runTest {
        val inbox = repository().inbox()

        assertTrue(inbox.isNotEmpty())
        assertTrue(inbox.any { it.unread })
        assertTrue(inbox.any { it.outgoing })
        assertTrue("dates should parse", inbox.all { it.date.startsWith("2026-") })
    }

    @Test
    fun `a sent message appears at the top of the inbox`() = runTest {
        val repository = repository()
        repository.sendSms("0800", "café ☕")

        val top = repository.inbox().first()
        assertEquals("0800", top.number)
        assertEquals("café ☕", top.text)
        assertTrue(top.outgoing)
    }

    @Test
    fun `mark read and delete take effect`() = runTest {
        val repository = repository()
        val unread = repository.inbox().first { it.unread }

        repository.markRead(listOf(unread.id))
        assertEquals(false, repository.inbox().first { it.id == unread.id }.unread)

        repository.deleteSms(listOf(unread.id))
        assertTrue(repository.inbox().none { it.id == unread.id })
    }

    @Test
    fun `balance code answers outright`() = runTest {
        val result = repository().ussdSend("*310#")
        assertEquals(UssdState.COMPLETE, result.state)
        assertTrue(result.text.contains("balance"))
    }

    @Test
    fun `other codes open a menu that takes replies`() = runTest {
        val repository = repository()

        val menu = repository.ussdSend("*323#")
        assertEquals(UssdState.PROMPT, menu.state)
        assertTrue(menu.text.contains("1. Data bundles"))

        assertEquals(UssdState.PROMPT, repository.ussdReply("1").state)
        assertEquals(UssdState.COMPLETE, repository.ussdReply("2").state)
    }
}
