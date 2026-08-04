package com.example.mde

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Best-Effort-Netzwerkdiagnose für fehlgeschlagene OTA-Kerberos-/SMB-Aufrufe.
 *
 * Alle Probes starten auf dem Android-Gerät im aktiven Netzwerk und kontaktieren
 * ausschließlich die konfigurierten SMB- und KDC-Ziele. Der ursprüngliche
 * OTA-Fehler bleibt immer maßgeblich.
 */
internal object OtaNetworkDiagnostics {

    fun capture(context: Context): Snapshot = try {
        AndroidProbe(context.applicationContext).snapshot()
    } catch (error: Exception) {
        Snapshot.unavailable(error)
    }

    fun logFailure(
        context: Context,
        beforeNativeCall: Snapshot,
        smbHost: String,
        kdcAddress: String,
        originalError: IOException,
        nativeDurationMillis: Long
    ) {
        OtaDiagnosticLog.warning(
            context = context,
            stage = "Netzwerkdiagnose/Auslöser",
            message = "Nativer Kerberos/SMB-Aufruf nach $nativeDurationMillis ms fehlgeschlagen; " +
                "automatische Diagnose auf dem aktiven Gerätenetz wird gestartet: " +
                "${originalError.javaClass.simpleName}: " +
                (originalError.message ?: "ohne Fehlermeldung")
        )
        try {
            val probe = AndroidProbe(context.applicationContext)
            val afterFailure = probe.snapshot()
            val smb = probe.diagnose(HostPortEndpoint(smbHost, OtaConfig.SMB_PORT))
            val kdc = probe.diagnose(
                parseHostPortEndpoint(
                    kdcAddress,
                    OtaConfig.KERBEROS_DEFAULT_PORT,
                    "Diagnose-KDC"
                )
            )
            val entries = buildEntries(
                beforeNativeCall = beforeNativeCall,
                afterFailure = afterFailure,
                smb = smb,
                kdc = kdc,
                originalError = originalError
            )
            entries.forEach { entry ->
                when (entry.severity) {
                    Severity.EVENT -> OtaDiagnosticLog.event(
                        context,
                        entry.stage,
                        entry.message
                    )
                    Severity.WARNING -> OtaDiagnosticLog.warning(
                        context,
                        entry.stage,
                        entry.message
                    )
                }
            }
            val status = buildCompactSummary(originalError, smb, kdc)
            OtaDiagnosticLog.summary(
                context = context,
                level = OtaDiagnosticLog.SummaryLevel.ERROR,
                title = status.title,
                lines = status.lines
            )
        } catch (diagnosticError: Exception) {
            OtaDiagnosticLog.warning(
                context = context,
                stage = "Netzwerkdiagnose/Fehler",
                message = "Automatische Gerätediagnose konnte nicht vollständig ausgeführt werden; " +
                    "der ursprüngliche OTA-Fehler bleibt maßgeblich",
                error = diagnosticError
            )
            OtaDiagnosticLog.summary(
                context = context,
                level = OtaDiagnosticLog.SummaryLevel.ERROR,
                title = "OTA BLOCKIERT",
                lines = listOf(
                    "STELLE: ${originalStage(originalError)}",
                    "PROBLEM: ${shortText(originalError.message ?: originalError.javaClass.simpleName)}",
                    "DIAGNOSE: Netzwerkprüfung selbst fehlgeschlagen",
                    "MASSNAHME: Technischen Eintrag in OTA_Details prüfen"
                )
            )
        }
    }

    internal fun classifyTcpFailure(error: Throwable): TcpStatus = when (error) {
        is SocketTimeoutException -> TcpStatus.TIMEOUT
        is NoRouteToHostException -> TcpStatus.NO_ROUTE
        is ConnectException -> {
            val message = error.message.orEmpty().lowercase(Locale.ROOT)
            when {
                "unreachable" in message || "no route" in message ||
                    "enetunreach" in message || "ehostunreach" in message -> TcpStatus.NO_ROUTE
                "refused" in message || "econnrefused" in message -> TcpStatus.REFUSED
                else -> TcpStatus.ERROR
            }
        }
        else -> TcpStatus.ERROR
    }

    internal fun addressMatchesPrefix(
        address: InetAddress,
        prefixAddress: InetAddress,
        prefixLength: Int
    ): Boolean {
        val addressBytes = address.address
        val prefixBytes = prefixAddress.address
        if (addressBytes.size != prefixBytes.size || prefixLength !in 0..addressBytes.size * 8) {
            return false
        }
        val completeBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until completeBytes) {
            if (addressBytes[index] != prefixBytes[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (addressBytes[completeBytes].toInt() and mask) ==
            (prefixBytes[completeBytes].toInt() and mask)
    }

    internal fun selectBestRoute(
        address: InetAddress,
        routes: List<DiagnosticRoute>
    ): DiagnosticRoute? = routes
        .asSequence()
        .filter { route ->
            addressMatchesPrefix(address, route.destination, route.prefixLength)
        }
        .maxByOrNull(DiagnosticRoute::prefixLength)

    internal fun buildEntries(
        beforeNativeCall: Snapshot,
        afterFailure: Snapshot,
        smb: EndpointResult,
        kdc: EndpointResult,
        originalError: IOException
    ): List<LogEntry> = buildList {
        add(
            LogEntry(
                Severity.EVENT,
                "Netzwerkdiagnose/Netz vor Native-Aufruf",
                formatSnapshot(beforeNativeCall)
            )
        )
        add(
            LogEntry(
                Severity.EVENT,
                "Netzwerkdiagnose/Netz nach Fehler",
                formatSnapshot(afterFailure)
            )
        )
        val networkChanged = beforeNativeCall.identity() != afterFailure.identity()
        add(
            LogEntry(
                if (networkChanged) Severity.WARNING else Severity.EVENT,
                "Netzwerkdiagnose/Netzwechsel",
                if (networkChanged) {
                    "Aktives Netzwerk, Interface oder lokale Adressen haben sich während " +
                        "des nativen Aufrufs geändert"
                } else {
                    "Kein Wechsel des aktiven Netzwerks während des nativen Aufrufs erkannt"
                }
            )
        )
        addEndpointEntries("SMB", smb, afterFailure)
        addEndpointEntries("KDC", kdc, afterFailure)
        add(
            LogEntry(
                Severity.WARNING,
                "Netzwerkdiagnose/Ergebnis",
                buildConclusion(originalError, smb, kdc)
            )
        )
    }

    private fun MutableList<LogEntry>.addEndpointEntries(
        label: String,
        result: EndpointResult,
        snapshot: Snapshot
    ) {
        add(
            LogEntry(
                if (result.dns.status == DnsStatus.OK) Severity.EVENT else Severity.WARNING,
                "Netzwerkdiagnose/$label/DNS",
                formatDns(result)
            )
        )

        val primaryAddress = result.dns.addresses.firstOrNull()
        add(
            LogEntry(
                Severity.EVENT,
                "Netzwerkdiagnose/$label/Route",
                formatRoute(primaryAddress, snapshot)
            )
        )

        val tcpSeverity = if (result.tcp?.status == TcpStatus.OPEN) {
            Severity.EVENT
        } else {
            Severity.WARNING
        }
        add(
            LogEntry(
                tcpSeverity,
                "Netzwerkdiagnose/$label/TCP",
                formatTcp(result)
            )
        )
    }

    private fun formatSnapshot(snapshot: Snapshot): String {
        if (!snapshot.available) {
            return "Aktives Netzwerk nicht verfügbar" +
                snapshot.error?.let { "; Diagnosefehler=$it" }.orEmpty()
        }
        val addresses = snapshot.addresses
            .take(MAX_LOGGED_ADDRESSES)
            .joinToString(", ") { it.display() }
            .ifEmpty { "keine" }
        val dnsServers = snapshot.dnsServers
            .take(MAX_LOGGED_DNS_SERVERS)
            .joinToString(", ") { it.display() }
            .ifEmpty { "keine" }
        val routes = snapshot.routes
            .take(MAX_LOGGED_ROUTES)
            .joinToString(" | ", transform = DiagnosticRoute::display)
            .ifEmpty { "keine" }
        return "Aktiv=true; Netz-ID=${snapshot.networkId ?: "unbekannt"}; " +
            "Transport=${snapshot.transports.joinToString("+").ifEmpty { "unbekannt" }}; " +
            "Interface=${snapshot.interfaceName ?: "unbekannt"}; " +
            "INTERNET=${snapshot.hasInternet}; VALIDATED=${snapshot.validated}; " +
            "CAPTIVE_PORTAL=${snapshot.captivePortal}; GETAKTET=${snapshot.metered}; " +
            "lokale Adressen=$addresses; DNS=$dnsServers; Routen=$routes"
    }

    private fun formatDns(result: EndpointResult): String {
        val dns = result.dns
        val addresses = dns.addresses
            .take(MAX_LOGGED_ADDRESSES)
            .joinToString(", ") { it.display() }
            .ifEmpty { "keine" }
        return "Host=${result.endpoint.host}; Ergebnis=${dns.status}; " +
            "Adressen=$addresses; Dauer=${dns.durationMillis} ms" +
            dns.errorDescription?.let { "; Fehler=$it" }.orEmpty()
    }

    private fun formatRoute(address: InetAddress?, snapshot: Snapshot): String {
        if (address == null) return "Keine Ziel-IP vorhanden; Route nicht bestimmbar"
        val sameSubnet = snapshot.addresses.firstOrNull { local ->
            addressMatchesPrefix(address, local.address, local.prefixLength)
        }
        val route = selectBestRoute(address, snapshot.routes)
        val sameFamilyAvailable = snapshot.addresses.any { local ->
            local.address.address.size == address.address.size
        }
        val subnetText = when {
            sameSubnet != null -> "ja (${sameSubnet.display()})"
            sameFamilyAvailable -> "nein"
            else -> "unbekannt"
        }
        return "Ziel-IP=${address.display()}; gleiches lokales Subnetz=$subnetText; " +
            "Route=${route?.display() ?: "keine passende Route in LinkProperties"}"
    }

    private fun formatTcp(result: EndpointResult): String {
        val tcp = result.tcp
            ?: return "Ziel=${result.endpoint.display()}; nicht ausgeführt, weil DNS keine Ziel-IP lieferte"
        return "Ziel=${tcp.remoteAddress.display()}:${result.endpoint.port}; " +
            "Ergebnis=${tcp.status}; Dauer=${tcp.durationMillis} ms; " +
            "lokale Quell-IP=${tcp.localAddress?.display() ?: "unbekannt"}" +
            tcp.errorDescription?.let { "; Fehler=$it" }.orEmpty()
    }

    private fun buildConclusion(
        originalError: IOException,
        smb: EndpointResult,
        kdc: EndpointResult
    ): String {
        val smbPort = smb.endpoint.port
        val smbCode = endpointCode(smb)
        val kdcCode = endpointCode(kdc)
        val smbConclusion = when {
            smb.dns.status != DnsStatus.OK ->
                "SMB-Ziel konnte nicht zuverlässig aufgelöst werden; DNS beziehungsweise " +
                    "das aktive Gerätenetz prüfen."
            smb.tcp?.status == TcpStatus.TIMEOUT ->
                "SMB-Ziel wurde aufgelöst, aber TCP/$smbPort antwortet nicht. Das entspricht " +
                    "einem stillen DROP oder fehlenden Rückweg; mögliche Stellen sind " +
                    "Server-Firewall/Endpoint-Security, WLAN-/Switch-ACL oder Netzpfad."
            smb.tcp?.status == TcpStatus.REFUSED ->
                "SMB-Ziel ist erreichbar, lehnt TCP/$smbPort aber aktiv ab; SMB-Dienst, Listener " +
                    "und Server-Firewall prüfen."
            smb.tcp?.status == TcpStatus.NO_ROUTE ->
                "Für das SMB-Ziel besteht vom aktiven Gerätenetz kein nutzbarer Netzpfad."
            smb.tcp?.status == TcpStatus.OPEN ->
                "TCP/$smbPort war bei der Nachprüfung erreichbar; der ursprüngliche Fehler war " +
                    "vorübergehend oder liegt in einer späteren SMB-/Kerberos-Stufe."
            else ->
                "SMB-Netzprüfung lieferte keinen eindeutigen TCP-Befund."
        }
        val nativeStage = if (originalError.message.orEmpty().contains("SMB-Verbindung")) {
            " Beim Originalfehler wurden SMB-Aushandlung, Kerberos und Versionsdatei " +
                "noch nicht erreicht."
        } else {
            ""
        }
        val kdcPort = kdc.endpoint.port
        val kdcConclusion = when {
            kdc.dns.status != DnsStatus.OK ->
                " Zusatzbefund: KDC-DNS fehlgeschlagen."
            kdc.tcp?.status == TcpStatus.TIMEOUT ->
                " Zusatzbefund: KDC TCP/$kdcPort antwortet ebenfalls nicht; " +
                    "UDP/$kdcPort wurde nicht " +
                    "separat nachgewiesen."
            kdc.tcp?.status == TcpStatus.REFUSED ->
                " Zusatzbefund: KDC lehnt TCP/$kdcPort aktiv ab; " +
                    "UDP/$kdcPort wurde nicht separat geprüft."
            kdc.tcp?.status == TcpStatus.OPEN ->
                " KDC TCP/$kdcPort ist erreichbar; UDP/$kdcPort wurde nicht separat geprüft."
            kdc.tcp?.status == TcpStatus.NO_ROUTE ->
                " Zusatzbefund: Für den KDC besteht kein nutzbarer TCP-Netzpfad."
            else -> ""
        }
        return "Code=SMB_$smbCode; KDC_$kdcCode; Schlussfolgerung=$smbConclusion" +
            nativeStage + kdcConclusion +
            " Der genaue Verwerfer eines stillen Drops ist vom Client allein nicht bestimmbar."
    }

    internal fun buildCompactSummary(
        originalError: IOException,
        smb: EndpointResult,
        kdc: EndpointResult
    ): CompactSummary {
        val originalMessage = originalError.message.orEmpty()
        val isConnectionFailure = "SMB-Verbindung" in originalMessage
        val title = when {
            isConnectionFailure && smb.dns.status != DnsStatus.OK -> "OTA BLOCKIERT: DNS"
            isConnectionFailure -> "OTA BLOCKIERT: NETZWERK"
            "SMB-Aushandlung" in originalMessage -> "OTA BLOCKIERT: SMB"
            "Kerberos-Anmeldung" in originalMessage -> "OTA BLOCKIERT: KERBEROS"
            "SMB-Freigabe" in originalMessage -> "OTA BLOCKIERT: FREIGABE"
            "SMB-Datei" in originalMessage -> "OTA BLOCKIERT: DATEI"
            else -> "OTA BLOCKIERT"
        }

        val lines = if (isConnectionFailure) {
            listOf(
                compactEndpointLine("SMB", smb),
                compactEndpointLine("KERBEROS", kdc),
                "STAND: SMB-Aushandlung, Anmeldung und version.json nicht erreicht",
                "MASSNAHME: ${connectionAction(smb, kdc)}"
            )
        } else {
            listOf(
                "FEHLER: ${shortText(originalMessage.ifEmpty { originalError.javaClass.simpleName })}",
                compactEndpointLine("SMB", smb),
                compactEndpointLine("KERBEROS", kdc),
                "MASSNAHME: ${laterStageAction(originalMessage, kdc)}"
            )
        }
        return CompactSummary(title, lines)
    }

    private fun compactEndpointLine(label: String, result: EndpointResult): String {
        val address = result.dns.addresses.firstOrNull()?.display()
        val target = if (address == null) {
            result.endpoint.display()
        } else {
            "${result.endpoint.host} ($address):${result.endpoint.port}"
        }
        val state = when {
            result.dns.status != DnsStatus.OK -> "DNS ${result.dns.status}"
            result.tcp == null -> "NICHT GETESTET"
            else -> result.tcp.status.toString()
        }
        return "$label: $target – $state"
    }

    private fun connectionAction(smb: EndpointResult, kdc: EndpointResult): String = when {
        smb.dns.status == DnsStatus.NOT_FOUND ->
            "SMB-Hostname und DNS-Eintrag im Gerätenetz prüfen"
        smb.dns.status == DnsStatus.TIMEOUT ->
            "DNS-Erreichbarkeit im aktiven Gerätenetz prüfen"
        smb.tcp?.status == TcpStatus.REFUSED ->
            "SMB-Dienst, TCP-${smb.endpoint.port}-Listener und Server-Firewall prüfen"
        smb.tcp?.status == TcpStatus.NO_ROUTE ->
            "WLAN-/VLAN-Route und Gateway zum SMB-Ziel prüfen"
        smb.tcp?.status == TcpStatus.TIMEOUT &&
            kdc.tcp?.status in setOf(TcpStatus.TIMEOUT, TcpStatus.NO_ROUTE) ->
            "Firewall/ACL/Rückweg für TCP ${smb.endpoint.port} sowie " +
                "UDP/TCP ${kdc.endpoint.port} prüfen"
        smb.tcp?.status == TcpStatus.TIMEOUT ->
            "Firewall/ACL/Rückweg für TCP ${smb.endpoint.port} vom Gerätenetz prüfen"
        else -> "SMB-Ziel und Netzpfad anhand OTA_Details prüfen"
    }

    private fun laterStageAction(originalMessage: String, kdc: EndpointResult): String = when {
        "Kerberos-Anmeldung" in originalMessage && kdc.tcp?.status == TcpStatus.TIMEOUT ->
            "KDC-Zugriff über UDP/TCP ${kdc.endpoint.port} vom Gerätenetz freigeben"
        "code 7" in originalMessage || "KRB-ERROR 7" in originalMessage ->
            "Kerberos-SPN cifs/<Server> und OTA-SMB-Server in den App-Einstellungen abgleichen"
        "code 24" in originalMessage || "KRB-ERROR 24" in originalMessage ->
            "OTA-Konto und Kennwort beziehungsweise Pre-Authentication prüfen"
        "code 37" in originalMessage || "KRB-ERROR 37" in originalMessage ->
            "Geräte-, KDC- und Serverzeit synchronisieren"
        "STATUS_BAD_NETWORK_NAME" in originalMessage ->
            "OTA-Freigabe in den App-Einstellungen mit dem Server abgleichen"
        "STATUS_ACCESS_DENIED" in originalMessage ->
            "Freigabe- und Dateirechte des OTA-Kontos prüfen"
        "STATUS_OBJECT_NAME_NOT_FOUND" in originalMessage ||
            "STATUS_OBJECT_PATH_NOT_FOUND" in originalMessage ->
            "OTA-Basispfad in den App-Einstellungen mit der Dateiablage abgleichen"
        "SMB-Aushandlung" in originalMessage ->
            "SMB2/3-Konfiguration und Serverantwort prüfen"
        else -> "genannte Stufe und Kurzfehler prüfen"
    }

    private fun originalStage(error: IOException): String = when {
        "SMB-Verbindung" in error.message.orEmpty() -> "Netzwerk zum SMB-Server"
        "SMB-Aushandlung" in error.message.orEmpty() -> "SMB-Aushandlung"
        "Kerberos-Anmeldung" in error.message.orEmpty() -> "Kerberos-Anmeldung"
        "SMB-Freigabe" in error.message.orEmpty() -> "SMB-Freigabe"
        "SMB-Datei" in error.message.orEmpty() -> "OTA-Datei"
        else -> "Kerberos/SMB"
    }

    private fun shortText(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').take(MAX_STATUS_TEXT_LENGTH)

    private fun endpointCode(result: EndpointResult): String = when {
        result.dns.status != DnsStatus.OK -> "DNS_${result.dns.status}"
        result.tcp == null -> "TCP_NOT_RUN"
        else -> "DNS_OK_TCP_${result.tcp.status}"
    }

    private class AndroidProbe(context: Context) {
        private val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java)
        private val activeNetwork: Network? = connectivityManager?.activeNetwork

        fun snapshot(): Snapshot {
            val network = activeNetwork ?: return Snapshot.unavailable()
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            val linkProperties = connectivityManager?.getLinkProperties(network)
            return Snapshot(
                available = true,
                networkId = network.toString(),
                transports = capabilities.transportNames(),
                hasInternet = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) == true,
                validated = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true,
                captivePortal = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                ) == true,
                metered = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                ) != true,
                interfaceName = linkProperties?.interfaceName,
                addresses = linkProperties.toDiagnosticAddresses(),
                dnsServers = linkProperties?.dnsServers.orEmpty(),
                routes = linkProperties.toDiagnosticRoutes()
            )
        }

        fun diagnose(endpoint: HostPortEndpoint): EndpointResult {
            val dns = resolve(endpoint.host)
            val primaryAddress = dns.addresses.firstOrNull()
            val tcp = primaryAddress?.let { address -> probeTcp(address, endpoint.port) }
            return EndpointResult(endpoint, dns, tcp)
        }

        private fun resolve(host: String): DnsResult {
            val startedNanos = System.nanoTime()
            val task = FutureTask {
                (activeNetwork?.getAllByName(host) ?: InetAddress.getAllByName(host))
                    .distinctBy { it.display() }
            }
            Thread(task, "OTA-Netzdiagnose-DNS").apply {
                isDaemon = true
                start()
            }
            return try {
                val addresses = task.get(DNS_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
                DnsResult(
                    status = if (addresses.isEmpty()) DnsStatus.ERROR else DnsStatus.OK,
                    addresses = addresses,
                    durationMillis = elapsedMillis(startedNanos),
                    errorDescription = if (addresses.isEmpty()) "Resolver lieferte keine Adresse" else null
                )
            } catch (_: TimeoutException) {
                task.cancel(true)
                DnsResult(
                    DnsStatus.TIMEOUT,
                    emptyList(),
                    elapsedMillis(startedNanos),
                    "DNS-Auflösung nach $DNS_TIMEOUT_MILLIS ms abgebrochen"
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                task.cancel(true)
                DnsResult(
                    DnsStatus.ERROR,
                    emptyList(),
                    elapsedMillis(startedNanos),
                    "DNS-Auflösung unterbrochen"
                )
            } catch (error: ExecutionException) {
                val cause = error.cause ?: error
                DnsResult(
                    if (cause is UnknownHostException) DnsStatus.NOT_FOUND else DnsStatus.ERROR,
                    emptyList(),
                    elapsedMillis(startedNanos),
                    describeError(cause)
                )
            } finally {
                if (!task.isDone) task.cancel(true)
            }
        }

        private fun probeTcp(address: InetAddress, port: Int): TcpResult {
            val startedNanos = System.nanoTime()
            var localAddress: InetAddress? = null
            val socket = activeNetwork?.socketFactory?.createSocket() ?: Socket()
            return try {
                socket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MILLIS)
                localAddress = socket.localAddress?.takeUnless(InetAddress::isAnyLocalAddress)
                TcpResult(
                    TcpStatus.OPEN,
                    address,
                    localAddress,
                    elapsedMillis(startedNanos),
                    null
                )
            } catch (error: Exception) {
                localAddress = socket.localAddress?.takeUnless(InetAddress::isAnyLocalAddress)
                TcpResult(
                    classifyTcpFailure(error),
                    address,
                    localAddress,
                    elapsedMillis(startedNanos),
                    describeError(error)
                )
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Ein Diagnosefehler darf den ursprünglichen OTA-Fehler nie verdecken.
                }
            }
        }
    }

    internal data class DiagnosticAddress(
        val address: InetAddress,
        val prefixLength: Int
    ) {
        fun display(): String = "${address.display()}/$prefixLength"
    }

    internal data class DiagnosticRoute(
        val destination: InetAddress,
        val prefixLength: Int,
        val gateway: InetAddress?,
        val interfaceName: String?
    ) {
        fun display(): String {
            val nextHop = gateway?.let { "via ${it.display()}" } ?: "direkt"
            return "${destination.display()}/$prefixLength $nextHop über " +
                (interfaceName ?: "unbekannt")
        }
    }

    internal data class Snapshot(
        val available: Boolean,
        val networkId: String? = null,
        val transports: List<String> = emptyList(),
        val hasInternet: Boolean = false,
        val validated: Boolean = false,
        val captivePortal: Boolean = false,
        val metered: Boolean = false,
        val interfaceName: String? = null,
        val addresses: List<DiagnosticAddress> = emptyList(),
        val dnsServers: List<InetAddress> = emptyList(),
        val routes: List<DiagnosticRoute> = emptyList(),
        val error: String? = null
    ) {
        fun identity(): Triple<String?, String?, List<String>> = Triple(
            networkId,
            interfaceName,
            addresses.map(DiagnosticAddress::display)
        )

        companion object {
            fun unavailable(error: Exception? = null): Snapshot = Snapshot(
                available = false,
                error = error?.let(::describeError)
            )
        }
    }

    internal enum class DnsStatus { OK, NOT_FOUND, TIMEOUT, ERROR }

    internal data class DnsResult(
        val status: DnsStatus,
        val addresses: List<InetAddress>,
        val durationMillis: Long,
        val errorDescription: String?
    )

    internal enum class TcpStatus { OPEN, TIMEOUT, REFUSED, NO_ROUTE, ERROR }

    internal data class TcpResult(
        val status: TcpStatus,
        val remoteAddress: InetAddress,
        val localAddress: InetAddress?,
        val durationMillis: Long,
        val errorDescription: String?
    )

    internal data class EndpointResult(
        val endpoint: HostPortEndpoint,
        val dns: DnsResult,
        val tcp: TcpResult?
    )

    internal enum class Severity { EVENT, WARNING }

    internal data class LogEntry(
        val severity: Severity,
        val stage: String,
        val message: String
    )

    internal data class CompactSummary(
        val title: String,
        val lines: List<String>
    )

    private fun NetworkCapabilities?.transportNames(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WLAN")
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("MOBILFUNK")
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        }
    }

    private fun LinkProperties?.toDiagnosticAddresses(): List<DiagnosticAddress> =
        this?.linkAddresses.orEmpty().map { linkAddress ->
            DiagnosticAddress(linkAddress.address, linkAddress.prefixLength)
        }

    private fun LinkProperties?.toDiagnosticRoutes(): List<DiagnosticRoute> {
        val interfaceName = this?.interfaceName
        return this?.routes.orEmpty().map { route ->
            DiagnosticRoute(
                destination = route.destination.address,
                prefixLength = route.destination.prefixLength,
                gateway = route.gateway?.takeUnless(InetAddress::isAnyLocalAddress),
                interfaceName = interfaceName
            )
        }
    }

    private fun InetAddress.display(): String = hostAddress ?: toString()

    private fun describeError(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: "ohne Meldung"}"

    private fun elapsedMillis(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

    private const val DNS_TIMEOUT_MILLIS = 2_000
    private const val TCP_TIMEOUT_MILLIS = 2_000
    private const val MAX_LOGGED_ADDRESSES = 6
    private const val MAX_LOGGED_DNS_SERVERS = 4
    private const val MAX_LOGGED_ROUTES = 6
    private const val MAX_STATUS_TEXT_LENGTH = 180
}
