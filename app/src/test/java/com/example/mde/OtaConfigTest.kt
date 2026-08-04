package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OtaConfigTest {

    @Test
    fun serverConfig_normalizesRuntimeValuesAndRedactsPassword() {
        val config = OtaServerConfig(
            server = "  mde-server  ",
            connectHost = "  mde-server.example.test  ",
            share = "  updates  ",
            basePath = "releases/android",
            realm = "example.test",
            kdcAddress = "kdc.example.test",
            username = "EXAMPLE\\ota-reader",
            password = "not-logged"
        )

        assertEquals("mde-server", config.server)
        assertEquals("mde-server.example.test", config.connectHost)
        assertEquals("updates", config.share)
        assertEquals("releases/android", config.basePath)
        assertEquals("EXAMPLE.TEST", config.realm)
        assertEquals("kdc.example.test:88", config.kdcAddress)
        assertEquals("ota-reader", config.username)
        assertFalse(config.toString().contains(config.password))
        assertFalse(config.toString().contains(config.username))
    }

    @Test
    fun normalizeOtaKdcAddress_keepsExplicitPort() {
        assertEquals(
            "kdc.example.test:1088",
            normalizeOtaKdcAddress("kdc.example.test:1088")
        )
    }

    @Test
    fun normalizeOtaKdcAddress_rejectsInvalidExplicitPortsAndUrls() {
        listOf(
            "kdc.example.test:",
            "kdc.example.test:not-a-port",
            "kdc.example.test:0",
            "kdc.example.test:65536",
            "https://kdc.example.test"
        ).forEach { value ->
            assertFails<IllegalArgumentException> { normalizeOtaKdcAddress(value) }
        }
    }

    @Test
    fun normalizeOtaKdcAddress_rejectsMalformedIpv6() {
        assertFails<IllegalArgumentException> { normalizeOtaKdcAddress("[:::]") }
        assertFails<IllegalArgumentException> { normalizeOtaKdcAddress("[abc]") }
        assertFails<IllegalArgumentException> { normalizeOtaKdcAddress("[2001:db8::1]") }
    }

    @Test
    fun normalizeKerberosUsername_acceptsDomainAndUpnForms() {
        assertEquals("tobias", normalizeKerberosUsername("CORP\\tobias"))
        assertEquals("tobias", normalizeKerberosUsername("tobias@CORP.EXAMPLE"))
        assertEquals("tobias", normalizeKerberosUsername("tobias"))
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Erwartete Exception ${T::class.java.simpleName} wurde nicht ausgelöst")
    }
}
