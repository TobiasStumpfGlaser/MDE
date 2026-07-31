package com.example.mde

import org.junit.Assert.assertEquals
import org.junit.Test

class KerbHandlerTest {

    @Test
    fun parseKrbConfig_readsRealmAndKdcFromMatchingSections() {
        val config = """
            default_realm = WRONG.EXAMPLE

            [libdefaults]
                default_realm = brainware-solutions.de
                dns_lookup_kdc = false

            [realms]
                OTHER.EXAMPLE = {
                    kdc = other.example:1088
                }
                BRAINWARE-SOLUTIONS.DE = {
                    kdc = domaincontroller.brainware-solutions.de
                    admin_server = ignored.brainware-solutions.de
                }
        """.trimIndent()

        assertEquals(
            ParsedKrbConfig(
                defaultRealm = "BRAINWARE-SOLUTIONS.DE",
                kdcAddress = "domaincontroller.brainware-solutions.de:88"
            ),
            parseKrbConfig(config)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseKrbConfig_rejectsMissingRealm() {
        parseKrbConfig("[libdefaults]\ndns_lookup_kdc = true")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseKrbConfig_rejectsMissingKdcForSelectedRealm() {
        parseKrbConfig(
            """
                [libdefaults]
                    default_realm = CORP.EXAMPLE
                [realms]
                    OTHER.EXAMPLE = {
                        kdc = dc.other.example
                    }
            """.trimIndent()
        )
    }

    @Test
    fun parseKrbConfig_keepsExplicitKdcPort() {
        val parsed = parseKrbConfig(
            """
                [libdefaults]
                    default_realm = CORP.EXAMPLE
                [realms]
                    CORP.EXAMPLE = {
                        kdc = dc.corp.example:1088
                    }
            """.trimIndent()
        )

        assertEquals("dc.corp.example:1088", parsed.kdcAddress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseKrbConfig_rejectsKdcUrl() {
        parseKrbConfig(
            """
                [libdefaults]
                    default_realm = CORP.EXAMPLE
                [realms]
                    CORP.EXAMPLE = {
                        kdc = https://dc.corp.example
                    }
            """.trimIndent()
        )
    }

    @Test
    fun normalizeKerberosUsername_acceptsDomainAndUpnForms() {
        assertEquals("tobias", normalizeKerberosUsername("CORP\\tobias"))
        assertEquals("tobias", normalizeKerberosUsername("tobias@CORP.EXAMPLE"))
        assertEquals("tobias", normalizeKerberosUsername("tobias"))
    }
}
