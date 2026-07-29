package dev.zlt.companion.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSettingsTest {

    @Test
    fun `not configured without a password`() {
        assertFalse(RouterSettings().isConfigured)
    }

    @Test
    fun `configured with a valid host and a password`() {
        assertTrue(RouterSettings(host = "192.168.0.1", password = "secret").isConfigured)
    }

    @Test
    fun `demo mode counts as configured without any password`() {
        assertTrue(RouterSettings(password = "", demoMode = true).isConfigured)
    }

    @Test
    fun `demo mode is off by default`() {
        assertFalse(RouterSettings().demoMode)
    }
}
