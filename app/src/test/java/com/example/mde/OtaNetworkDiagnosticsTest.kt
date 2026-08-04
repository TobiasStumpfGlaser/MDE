package com.example.mde

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaNetworkDiagnosticsTest {

    @Test
    fun parseEndpoint_supportsDnsIpv4AndBracketedIpv6() {
        assertEquals(
            HostPortEndpoint("kdc.example.test", 88),
            parseHostPortEndpoint("kdc.example.test", 88, "Test-Endpunkt")
        )
        assertEquals(
            HostPortEndpoint("192.168.30.19", 445),
            parseHostPortEndpoint("192.168.30.19:445", 88, "Test-Endpunkt")
        )
        assertEquals(
            HostPortEndpoint("2001:db8::14", 88),
            parseHostPortEndpoint("[2001:db8::14]:88", 445, "Test-Endpunkt")
        )
    }

    @Test
    fun parseEndpoint_rejectsInvalidExplicitPorts() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHostPortEndpoint("kdc.example.test:not-a-port", 88, "Test-Endpunkt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseHostPortEndpoint("[2001:db8::14]:not-a-port", 88, "Test-Endpunkt")
        }
    }

    @Test
    fun addressMatchesPrefix_distinguishesLocalAndRoutedIpv4Targets() {
        val localPrefix = ip("192.168.30.82")

        assertTrue(
            OtaNetworkDiagnostics.addressMatchesPrefix(
                ip("192.168.30.19"),
                localPrefix,
                24
            )
        )
        assertFalse(
            OtaNetworkDiagnostics.addressMatchesPrefix(
                ip("192.168.200.14"),
                localPrefix,
                24
            )
        )
        assertTrue(
            OtaNetworkDiagnostics.addressMatchesPrefix(
                ip("192.168.31.20"),
                localPrefix,
                23
            )
        )
    }

    @Test
    fun addressMatchesPrefix_supportsIpv6() {
        assertTrue(
            OtaNetworkDiagnostics.addressMatchesPrefix(
                ip("2001:db8:1::20"),
                ip("2001:db8:1::82"),
                64
            )
        )
        assertFalse(
            OtaNetworkDiagnostics.addressMatchesPrefix(
                ip("2001:db8:2::20"),
                ip("2001:db8:1::82"),
                64
            )
        )
    }

    @Test
    fun selectBestRoute_prefersSpecificDirectRouteOverDefaultGateway() {
        val routes = listOf(
            OtaNetworkDiagnostics.DiagnosticRoute(
                destination = ip("0.0.0.0"),
                prefixLength = 0,
                gateway = ip("192.168.30.254"),
                interfaceName = "wlan0"
            ),
            OtaNetworkDiagnostics.DiagnosticRoute(
                destination = ip("192.168.30.0"),
                prefixLength = 24,
                gateway = null,
                interfaceName = "wlan0"
            )
        )

        val selected = OtaNetworkDiagnostics.selectBestRoute(ip("192.168.30.19"), routes)

        assertNotNull(selected)
        assertEquals(24, selected?.prefixLength)
        assertEquals(null, selected?.gateway)
    }

    @Test
    fun classifyTcpFailure_mapsNetworkFailureKinds() {
        assertEquals(
            OtaNetworkDiagnostics.TcpStatus.TIMEOUT,
            OtaNetworkDiagnostics.classifyTcpFailure(SocketTimeoutException("timed out"))
        )
        assertEquals(
            OtaNetworkDiagnostics.TcpStatus.REFUSED,
            OtaNetworkDiagnostics.classifyTcpFailure(ConnectException("Connection refused"))
        )
        assertEquals(
            OtaNetworkDiagnostics.TcpStatus.NO_ROUTE,
            OtaNetworkDiagnostics.classifyTcpFailure(NoRouteToHostException("No route"))
        )
    }

    @Test
    fun buildEntries_explainsSmbTimeoutAndKdcSecondBlocker() {
        val deviceAddress = ip("192.168.30.82")
        val smbAddress = ip("192.168.30.19")
        val kdcAddress = ip("192.168.200.14")
        val snapshot = OtaNetworkDiagnostics.Snapshot(
            available = true,
            networkId = "102",
            transports = listOf("WLAN"),
            hasInternet = true,
            validated = false,
            captivePortal = false,
            metered = false,
            interfaceName = "wlan0",
            addresses = listOf(OtaNetworkDiagnostics.DiagnosticAddress(deviceAddress, 24)),
            dnsServers = listOf(ip("192.168.30.254")),
            routes = listOf(
                OtaNetworkDiagnostics.DiagnosticRoute(
                    ip("192.168.30.0"),
                    24,
                    null,
                    "wlan0"
                ),
                OtaNetworkDiagnostics.DiagnosticRoute(
                    ip("0.0.0.0"),
                    0,
                    ip("192.168.30.254"),
                    "wlan0"
                )
            )
        )
        val smb = timeoutResult("mde-server.example.test", 445, smbAddress, deviceAddress)
        val kdc = timeoutResult("kdc.example.test", 88, kdcAddress, deviceAddress)

        val entries = OtaNetworkDiagnostics.buildEntries(
            beforeNativeCall = snapshot,
            afterFailure = snapshot,
            smb = smb,
            kdc = kdc,
            originalError = IOException("SMB-Verbindung: Operation timed out")
        )

        val messagesByStage = entries.associate { it.stage to it.message }
        assertTrue(messagesByStage.getValue("Netzwerkdiagnose/SMB/DNS").contains("192.168.30.19"))
        assertTrue(messagesByStage.getValue("Netzwerkdiagnose/SMB/Route").contains("gleiches lokales Subnetz=ja"))
        assertTrue(messagesByStage.getValue("Netzwerkdiagnose/SMB/TCP").contains("Ergebnis=TIMEOUT"))
        assertTrue(messagesByStage.getValue("Netzwerkdiagnose/KDC/TCP").contains("Ergebnis=TIMEOUT"))
        val conclusion = messagesByStage.getValue("Netzwerkdiagnose/Ergebnis")
        assertTrue(conclusion.contains("Code=SMB_DNS_OK_TCP_TIMEOUT"))
        assertTrue(conclusion.contains("KDC_DNS_OK_TCP_TIMEOUT"))
        assertTrue(conclusion.contains("Kerberos und Versionsdatei noch nicht erreicht"))
        assertTrue(conclusion.contains("UDP/88 wurde nicht separat nachgewiesen"))

        val compact = OtaNetworkDiagnostics.buildCompactSummary(
            IOException("SMB-Verbindung: Operation timed out"),
            smb,
            kdc
        )
        assertEquals("OTA BLOCKIERT: NETZWERK", compact.title)
        assertEquals(
            listOf(
                "SMB: mde-server.example.test (192.168.30.19):445 – TIMEOUT",
                "KERBEROS: kdc.example.test (192.168.200.14):88 – TIMEOUT",
                "STAND: SMB-Aushandlung, Anmeldung und version.json nicht erreicht",
                "MASSNAHME: Firewall/ACL/Rückweg für TCP 445 sowie UDP/TCP 88 prüfen"
            ),
            compact.lines
        )
    }

    private fun timeoutResult(
        host: String,
        port: Int,
        remoteAddress: InetAddress,
        localAddress: InetAddress
    ): OtaNetworkDiagnostics.EndpointResult = OtaNetworkDiagnostics.EndpointResult(
        endpoint = HostPortEndpoint(host, port),
        dns = OtaNetworkDiagnostics.DnsResult(
            status = OtaNetworkDiagnostics.DnsStatus.OK,
            addresses = listOf(remoteAddress),
            durationMillis = 4,
            errorDescription = null
        ),
        tcp = OtaNetworkDiagnostics.TcpResult(
            status = OtaNetworkDiagnostics.TcpStatus.TIMEOUT,
            remoteAddress = remoteAddress,
            localAddress = localAddress,
            durationMillis = 2_001,
            errorDescription = "SocketTimeoutException: timed out"
        )
    )

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
