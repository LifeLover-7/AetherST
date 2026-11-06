package io.github.immaghzbad.aetherst.core

import android.content.Context
import io.github.immaghzbad.aetherst.shared.data.IpInfoRepository
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

object LastMimHops {
    @Volatile var outer: String = ""
    @Volatile var inner: String = ""
    fun snapshot(): Pair<String, String> = Pair(outer, inner)
    fun clear() {
        outer = ""
        inner = ""
    }
}

private val MimOuterRegex = Regex("""selected\s+MASQUE\s+gateway\s+(\S+)""", RegexOption.IGNORE_CASE)
private val MimReadyRegex = Regex("""masque-in-masque\s+ready:?\s*(\S+)\s*\(outer\)\s+and\s+(\S+)\s*\(inner\)""", RegexOption.IGNORE_CASE)
private val ExitDetailRegex = Regex("""(?i)\b(?:gool|masque|wireguard|wg|mim)\s+exit:\s*([0-9a-fA-F.:]+),\s*([A-Za-z]{2})(?:\s+via\s+([A-Za-z0-9]+))?""")

class AetherProcessRunner(private val context: Context) {

    private val lock = Any()
    private var process: Process? = null
    private var runnerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentAttemptId = AtomicLong(0)
    private var goolOuterValidated = false
    private var dataPlaneOk = false
    private var lastBindAddress: String = "127.0.0.1:1819"
    private val isReconnecting = AtomicBoolean(false)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.STOPPED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val quickRetryPending = AtomicBoolean(false)

    fun start(config: AetherConfig, bindAddress: String, onCodeRequired: () -> Unit = {}, inputProvider: suspend () -> String = { "" }) {
        synchronized(lock) {
            if (runnerJob?.isActive == true) return

            val attemptId = currentAttemptId.incrementAndGet()
            goolOuterValidated = false
            lastBindAddress = bindAddress
            updateState(ConnectionStatus.STARTING, attemptId)
            runnerJob = scope.launch {
                var retryCount = 0

                while (isActive && (currentAttemptId.get() == attemptId)) {
                    if (!config.smartReconnect && retryCount > 0) {
                        LogRepository.e("Smart Reconnect disabled -> stopping retry")
                        updateState(ConnectionStatus.ERROR, attemptId)
                        break
                    }
                    if (config.smartReconnect && (retryCount >= config.reconnectRetryLimit)) {
                        LogRepository.e("Smart Reconnect limit reached ($retryCount). Stopping...")
                        updateState(ConnectionStatus.ERROR, attemptId)
                        break
                    }

                    if (retryCount > 0) {
                        val waitTime = if (quickRetryPending.compareAndSet(true, false)) {
                            3000L
                        } else {
                            (retryCount * 1000L).coerceAtMost(10000L)
                        }
                        LogRepository.i("Recovering connection (Retry $retryCount)...")
                        updateState(ConnectionStatus.RECONNECTING, attemptId)
                        delay(waitTime.milliseconds)
                    } else {
                        LogRepository.i("Starting system core...")
                    }

                    if (currentAttemptId.get() != attemptId) break

                    try {
                        val result = runBinary(config, attemptId, bindAddress, onCodeRequired, inputProvider)
                        if (currentAttemptId.get() != attemptId) break
                        if (!result) {
                            LogRepository.e("Stability check failed. Retrying...")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogRepository.e("Execution cycle critical error: ${e.localizedMessage}")
                    }

                    retryCount++
                }
            }
        }
    }

    private suspend fun runBinary(config: AetherConfig, attemptId: Long, bindAddress: String, onCodeRequired: () -> Unit, inputProvider: suspend () -> String): Boolean = coroutineScope {
        var proc: Process? = null
        try {
            dataPlaneOk = false
            val binaryFile = BinaryManager.prepareBinary(context)
            if (currentAttemptId.get() != attemptId) return@coroutineScope true

            // در حالت GOOL اگر نویز روی OFF باشد، در ایران هندشیک وایرگارد بلاک می‌شود؛ پس خودکار روی balanced قرار می‌گیرد
            val effectiveNoise = if (config.protocol == AetherProtocol.GOOL && config.noise == AetherNoise.OFF) {
                AetherNoise.BALANCED.rawValue
            } else {
                config.noise.rawValue
            }

            val commandList = mutableListOf<String>()
            commandList.add(binaryFile.absolutePath)
            commandList.add("--bind")
            commandList.add(bindAddress)

            when (config.protocol) {
                AetherProtocol.MASQUE -> commandList.add("--masque")
                AetherProtocol.WG -> commandList.add("--wg")
                AetherProtocol.GOOL -> commandList.add("--gool")
                AetherProtocol.ZERO_TRUST -> {}
            }

            commandList.add("--scan")
            commandList.add(config.scanMode.rawValue)
            commandList.add("--noize")
            commandList.add(effectiveNoise)

            val routingFile = writeRoutingFile(config)
            if (routingFile != null) {
                commandList.add("--routes")
                commandList.add(routingFile.absolutePath)
            }

            val effectiveIp = if (config.protocol == AetherProtocol.GOOL && config.ipMode == AetherIpMode.AUTO) {
                AetherIpMode.IPV4
            } else {
                config.effectiveIpMode()
            }
            commandList.add(
                when (effectiveIp) {
                    AetherIpMode.IPV4 -> "-4"
                    AetherIpMode.IPV6 -> "-6"
                    else -> "--dual"
                }
            )

            val isMasqueBased = config.protocol == AetherProtocol.MASQUE || config.protocol == AetherProtocol.ZERO_TRUST || (config.protocol == AetherProtocol.GOOL && !config.goolClassic) || config.mimEnabled
            if (isMasqueBased) {
                if (config.h2Mode) commandList.add("--h2")
                if (config.echEnabled) {
                    commandList.add("--ech")
                    commandList.add("auto")
                }
                if (config.h2Fragment) {
                    commandList.add("--fragment")
                    commandList.add("--fragment-size")
                    commandList.add(config.fragmentSize.ifBlank { "30-60" })
                    commandList.add("--fragment-delay")
                    commandList.add(config.fragmentDelay.ifBlank { "5-15" })
                }
                val effectiveSni = if (config.masqueSni.isNotBlank()) {
                    config.masqueSni.trim()
                } else if (config.protocol != AetherProtocol.ZERO_TRUST) {
                    "consumer-masque.cloudflareclient.com"
                } else {
                    ""
                }
                if (effectiveSni.isNotBlank()) {
                    commandList.add("--masque-sni")
                    commandList.add(effectiveSni)
                }
            }


            if (config.httpProxyEnabled) {
                val httpBindHost = bindAddress.substringBefore(':')
                commandList.add("--http-proxy")
                commandList.add("$httpBindHost:${config.httpPort}")
            }

            if (config.noDataCheck) commandList.add("--no-data-check")
            if (config.quickReconnect) commandList.add("--quick-reconnect") else commandList.add("--no-quick-reconnect")

            val wiwOuter = if (config.protocol == AetherProtocol.GOOL) config.wiwOuter.trim().ifEmpty { config.peer.trim() } else ""
            val wiwInner = if (config.protocol == AetherProtocol.GOOL) config.wiwInner.trim().ifEmpty { config.wgPeer.trim() } else ""

            when (config.protocol) {
                AetherProtocol.GOOL -> {
                    if (config.goolClassic) {
                        commandList.add("--gool-classic")
                        if (wiwOuter.isNotEmpty()) {
                            commandList.add("--wiw-outer")
                            commandList.add(wiwOuter)
                        }
                        if (wiwInner.isNotEmpty()) {
                            commandList.add("--wiw-inner")
                            commandList.add(wiwInner)
                        }
                        if (config.wiwScan) {
                            commandList.add("--wiw-scan")
                        }
                    } else {
                        commandList.add("--gool")
                        if (wiwOuter.isNotEmpty()) {
                            commandList.add("--peer")
                            commandList.add(wiwOuter)
                        }
                        if (wiwInner.isNotEmpty()) {
                            commandList.add("--gool-peer")
                            commandList.add(wiwInner)
                        }
                    }
                }
                AetherProtocol.WG -> {
                    val wgPeer = config.wgPeer.trim().ifEmpty { config.peer.trim() }
                    if (wgPeer.isNotEmpty()) {
                        commandList.add("--peer")
                        commandList.add(wgPeer)
                        commandList.add("--wg-peer")
                        commandList.add(wgPeer)
                    }
                }
                else -> {
                    val peer = config.peer.trim()
                    if (peer.isNotEmpty()) {
                        commandList.add("--peer")
                        commandList.add(peer)
                    }
                }
            }

            if ((config.protocol == AetherProtocol.WG) || (config.protocol == AetherProtocol.GOOL)) {
                commandList.add("--keepalive")
                commandList.add(if (config.keepaliveEnabled) config.keepalive.toString() else "0")
            }

            if (config.tlsGroups.isNotEmpty()) {
                commandList.add("--tls-groups")
                commandList.add(config.tlsGroups)
            }

            commandList.add("--validate-secs")
            commandList.add(config.validateSecs.toString())

            commandList.add("--reconnect-secs")
            commandList.add(config.reconnectSecs.toString())

            if (config.noProfileRetry) commandList.add("--no-profile-retry")

            if (config.protocol == AetherProtocol.ZERO_TRUST) {
                if (config.teamName.isNotEmpty()) {
                    commandList.add("--team")
                    commandList.add(config.teamName)
                }
                when {
                    config.accessToken.isNotEmpty() -> {
                        commandList.add("--access-token")
                        commandList.add(config.accessToken)
                    }
                    config.accessId.isNotEmpty() || config.accessSecret.isNotEmpty() -> {
                        commandList.add("--access-id")
                        commandList.add(config.accessId)
                        commandList.add("--access-secret")
                        commandList.add(config.accessSecret)
                    }
                    config.accessEmail.isNotEmpty() -> {
                        commandList.add("--access-email")
                        commandList.add(config.accessEmail)
                    }
                }
                if (config.useGateway) commandList.add("--gateway")
            }

            if (config.dnsEnabled && config.dnsList.isNotEmpty()) {
                commandList.add("--dns")
                commandList.add(config.dnsList)
            }

            if (config.upstreamProxyEnabled && config.upstreamProxy.isNotEmpty()) {
                commandList.add("--upstream")
                commandList.add(config.upstreamProxy)
                if (config.upstreamProxy.startsWith("http://", ignoreCase = true) && isMasqueBased) commandList.add("--h2")
            }

            if (config.torEnabled) {
                when (config.torMode) {
                    TorMode.TOR -> commandList.add("--tor")
                    TorMode.TOR_REVERSE -> commandList.add("--tor-reverse")
                    TorMode.TOR_ONLY -> commandList.add("--tor-only")
                }
                val torPort = TorController.activePort(config)
                commandList.add("--tor-bind")
                commandList.add("127.0.0.1:$torPort")
                val torDir = java.io.File(context.filesDir, "tor")
                if (!torDir.exists()) torDir.mkdirs()
                commandList.add("--tor-dir")
                commandList.add(torDir.absolutePath)
                when (config.torBridgesMode.trim().lowercase()) {
                    "force" -> commandList.add("--tor-bridges")
                    "off" -> commandList.add("--no-tor-bridges")
                }
                config.torBridgeLines.split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    commandList.add("--tor-bridge")
                    commandList.add(it)
                }
                val ptDir = config.torPtDir.trim().ifEmpty { torDir.absolutePath }
                commandList.add("--tor-pt-dir")
                commandList.add(ptDir)
                config.torPtBinaries.split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    commandList.add("--tor-pt")
                    commandList.add(it)
                }
            }

            val mimActive = config.protocol == AetherProtocol.MASQUE && config.mimEnabled
            val hasMimManual = config.mimOuter.isNotBlank() || config.mimInner.isNotBlank()
            if (mimActive) {
                commandList.add("--mim")
                if (config.mimOuter.isNotBlank()) {
                    commandList.add("--mim-outer")
                    commandList.add(config.mimOuter.trim())
                }
                if (config.mimInner.isNotBlank()) {
                    commandList.add("--mim-inner")
                    commandList.add(config.mimInner.trim())
                }
                if (config.mimScan && !hasMimManual) commandList.add("--mim-scan")
            }

            if (!config.quicV2Probe) commandList.add("--no-quic-v2")
            if (config.firewallMark.isNotBlank()) {
                commandList.add("--mark")
                commandList.add(config.firewallMark.trim())
            }

            val logLevelArg = if (config.coreLogLevel == AetherLogLevel.DEBUG) "debug" else "info"

            val pb = ProcessBuilder(commandList)
            pb.directory(context.filesDir)

            val env = pb.environment()
            env["AETHER_PROTOCOL"] = config.protocol.rawValue
            env["AETHER_NOIZE"] = effectiveNoise
            env["AETHER_SCAN"] = config.scanMode.rawValue
            env["AETHER_IP"] = effectiveIp.rawValue
            env["AETHER_SOCKS"] = bindAddress
            routingFile?.let { env["AETHER_ROUTES_FILE"] = it.absolutePath }

            if (isMasqueBased) {
                if (config.h2Mode) env["AETHER_MASQUE_HTTP2"] = "1"
                if (config.echEnabled) env["AETHER_ECH"] = "auto"
                if (config.h2Fragment) {
                    env["AETHER_MASQUE_H2_FRAGMENT"] = "1"
                    env["AETHER_MASQUE_H2_FRAGMENT_SIZE"] = config.fragmentSize.ifBlank { "30-60" }
                    env["AETHER_MASQUE_H2_FRAGMENT_DELAY"] = config.fragmentDelay.ifBlank { "5-15" }
                }
                val effectiveSni = if (config.masqueSni.isNotBlank()) {
                    config.masqueSni.trim()
                } else if (config.protocol != AetherProtocol.ZERO_TRUST) {
                    "consumer-masque.cloudflareclient.com"
                } else {
                    ""
                }
                if (effectiveSni.isNotBlank()) {
                    env["AETHER_MASQUE_SNI"] = effectiveSni
                }
            }

            if (config.httpProxyEnabled) {
                val httpBindHost = bindAddress.substringBefore(':')
                env["AETHER_HTTP_PROXY"] = "$httpBindHost:${config.httpPort}"
            }

            if (config.noDataCheck) {
                env["AETHER_MASQUE_NO_DATA_CHECK"] = "1"
                env["AETHER_WG_NO_DATA_CHECK"] = "1"
            }

            env["AETHER_QUICK_RECONNECT"] = if (config.quickReconnect) "1" else "0"


            when (config.protocol) {
                AetherProtocol.GOOL -> {
                    if (config.goolClassic) {
                        env["AETHER_GOOL_MODE"] = "classic"
                        if (wiwOuter.isNotEmpty()) env["AETHER_WIW_OUTER_PEER"] = wiwOuter
                        if (wiwInner.isNotEmpty()) env["AETHER_WIW_INNER_PEER"] = wiwInner
                        if (config.wiwScan) env["AETHER_WIW_PEERS"] = "auto"
                    } else {
                        if (wiwOuter.isNotEmpty()) env["AETHER_PEER"] = wiwOuter
                        if (wiwInner.isNotEmpty()) env["AETHER_GOOL_PEER"] = wiwInner
                    }
                }
                AetherProtocol.WG -> {
                    val wgPeer = config.wgPeer.trim().ifEmpty { config.peer.trim() }
                    if (wgPeer.isNotEmpty()) {
                        env["AETHER_WG_PEER"] = wgPeer
                        env["AETHER_PEER"] = wgPeer
                    }
                }
                else -> {
                    if (config.peer.isNotEmpty()) env["AETHER_PEER"] = config.peer
                }
            }

            env["AETHER_WG_KEEPALIVE"] = if (config.keepaliveEnabled) config.keepalive.toString() else "0"
            env["AETHER_WG_ENDPOINT_COOLDOWN_SECS"] = config.wgEndpointCooldownSecs.toString()
            env["AETHER_MASQUE_VALIDATE_SECS"] = config.validateSecs.toString()
            env["AETHER_WG_VALIDATE_SECS"] = config.validateSecs.toString()
            env["AETHER_MASQUE_RECONNECT_SECS"] = config.reconnectSecs.toString()
            env["AETHER_WG_RECONNECT_SECS"] = config.reconnectSecs.toString()

            if (config.noProfileRetry) env["AETHER_WG_NO_PROFILE_RETRY"] = "1"
            if (config.tlsGroups.isNotEmpty()) env["AETHER_TLS_GROUPS"] = config.tlsGroups
            if (config.masqueMtu > 0) env["AETHER_MASQUE_MTU"] = config.masqueMtu.toString()
            if (config.netstackTcpRx > 0) env["AETHER_NETSTACK_TCP_RX"] = config.netstackTcpRx.toString()
            if (config.netstackTcpTx > 0) env["AETHER_NETSTACK_TCP_TX"] = config.netstackTcpTx.toString()

            if (config.protocol == AetherProtocol.ZERO_TRUST) {
                if (config.teamName.isNotEmpty()) env["AETHER_TEAM"] = config.teamName
                when {
                    config.accessToken.isNotEmpty() -> env["AETHER_ACCESS_TOKEN"] = config.accessToken
                    config.accessId.isNotEmpty() || config.accessSecret.isNotEmpty() -> {
                        env["AETHER_ACCESS_ID"] = config.accessId
                        env["AETHER_ACCESS_SECRET"] = config.accessSecret
                        env["AETHER_ACCESS_CLIENT_ID"] = config.accessId
                        env["AETHER_ACCESS_CLIENT_SECRET"] = config.accessSecret
                    }
                    config.accessEmail.isNotEmpty() -> env["AETHER_ACCESS_EMAIL"] = config.accessEmail
                }
                if (config.useGateway) env["AETHER_GATEWAY"] = "1"
            }

            if (config.dnsEnabled && config.dnsList.isNotEmpty()) env["AETHER_DNS"] = config.dnsList
            if (config.upstreamProxyEnabled && config.upstreamProxy.isNotEmpty()) env["AETHER_UPSTREAM"] = config.upstreamProxy
            if (config.torEnabled) {
                env["AETHER_TOR"] = when (config.torMode) {
                    TorMode.TOR -> "chain"
                    TorMode.TOR_REVERSE -> "reverse"
                    TorMode.TOR_ONLY -> "only"
                }
                val torPort = TorController.activePort(config)
                env["AETHER_TOR_BIND"] = "127.0.0.1:$torPort"
                val torDir = java.io.File(context.filesDir, "tor")
                if (!torDir.exists()) torDir.mkdirs()
                env["AETHER_TOR_DIR"] = torDir.absolutePath
                when (config.torBridgesMode.trim().lowercase()) {
                    "force" -> env["AETHER_TOR_BRIDGES"] = "auto"
                    "off" -> env["AETHER_TOR_BRIDGES"] = "off"
                }
                val manualBridges = config.torBridgeLines.split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (manualBridges.isNotEmpty()) env["AETHER_TOR_BRIDGES"] = manualBridges.joinToString(";")
                val ptBinaries = config.torPtBinaries.split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (ptBinaries.isNotEmpty()) env["AETHER_TOR_PT"] = ptBinaries.joinToString(";")
                env["AETHER_TOR_PT_DIR"] = config.torPtDir.trim().ifEmpty { torDir.absolutePath }
                if (config.torCountry.isNotBlank()) env["AETHER_TOR_COUNTRY"] = config.torCountry.trim().lowercase()
            }
            if (mimActive) {
                env["AETHER_PROTOCOL"] = "mim"
                if (config.mimOuter.isNotBlank()) env["AETHER_MIM_OUTER_PEER"] = config.mimOuter.trim()
                if (config.mimInner.isNotBlank()) env["AETHER_MIM_INNER_PEER"] = config.mimInner.trim()
                if (config.mimScan && !hasMimManual) env["AETHER_MIM_PEERS"] = "auto"
            }
            if (!config.quicV2Probe) env["AETHER_QUIC_V2"] = "0"
            if (config.firewallMark.isNotBlank()) env["AETHER_MARK"] = config.firewallMark.trim()
            if (config.halfCloseSecs > 0) env["AETHER_HALF_CLOSE_SECS"] = config.halfCloseSecs.toString()
            if (config.tcpKeepaliveSecs > 0) env["AETHER_TCP_KEEPALIVE_SECS"] = config.tcpKeepaliveSecs.toString()
            if (config.tcpConnectSecs > 0) env["AETHER_TCP_CONNECT_SECS"] = config.tcpConnectSecs.toString()
            if (config.maxClients > 0) env["AETHER_MAX_CLIENTS"] = config.maxClients.toString()
            env["AETHER_ROUTE_SNIFF"] = if (config.routeSniffing) "1" else "0"
            env["AETHER_ROUTE_SNIFF_MS"] = config.sniffingTimeoutMs.toString()
            env["AETHER_REPROVISION"] = if (config.reprovision) "1" else "0"

            env["AETHER_PERF_PROFILE"] = config.perfProfile.rawValue
            env["AETHER_LOG_LEVEL"] = logLevelArg

            pb.redirectErrorStream(true)
            proc = withContext(Dispatchers.IO) { pb.start() }

            synchronized(lock) {
                if (currentAttemptId.get() != attemptId) {
                    proc?.destroyForcibly()
                    return@coroutineScope true
                }
                process = proc
            }

            val inputJob = launch {
                val writer = BufferedWriter(OutputStreamWriter(proc!!.outputStream))
                try {
                    while (isActive) {
                        val text = inputProvider()
                        if (text.isNotEmpty()) {
                            writer.write(text)
                            writer.newLine()
                            writer.flush()
                        }
                    }
                } catch (_: Exception) {}
            }

            BufferedReader(InputStreamReader(proc!!.inputStream)).use { reader ->
                var line: String?
                while (currentCoroutineContext().isActive && (currentAttemptId.get() == attemptId)) {
                    line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        if (currentAttemptId.get() != attemptId) null else throw e
                    } ?: break

                    parseOutputLine(line, attemptId, config.protocol, onCodeRequired)
                }
            }

            inputJob.cancel()
            val exitCode = try { withContext(Dispatchers.IO) { proc.waitFor() } } catch (_: Exception) { -1 }
            if (currentAttemptId.get() == attemptId) {
                LogRepository.i("Core process terminated (Exit code: $exitCode)")
            }
            exitCode == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentAttemptId.get() == attemptId) {
                LogRepository.e("Binary runtime error: ${e.localizedMessage}")
                return@coroutineScope false
            }
            true
        } finally {
            synchronized(lock) {
                if (process === proc) process = null
            }
            try { proc?.destroyForcibly() } catch (_: Exception) {}
        }
    }

    private fun isZeroTrustCodePrompt(line: String): Boolean {
        return line.contains("code") && (
            line.contains("enter") || line.contains("login") || line.contains("verif") ||
            line.contains("confirm") || line.contains("otp") || line.contains("one-time") ||
            line.contains("type the") || line.contains("paste") || line.contains("prompt")
        )
    }

    private suspend fun parseOutputLine(line: String, attemptId: Long, protocol: AetherProtocol, onCodeRequired: () -> Unit) {
        if (currentAttemptId.get() != attemptId) return

        val lower = line.lowercase()

        val isBroken = lower.contains("broken pipe") || lower.contains("stream closed")
        if (isBroken) {
            if (!PsiphonController.isConnected() && !TorController.isConnected()) {
                delay(3000.milliseconds)
            }
        } else {
            val source = if (lower.contains("tor")) "Tor" else "AetherCore"
            when {
                lower.contains(" error ") || lower.contains("[error]") || lower.startsWith("error:") -> LogRepository.e(line, source)
                lower.contains(" warn ") || lower.contains("[warn]") -> LogRepository.w(line, source)
                else -> LogRepository.i(line, source)
            }
        }

        if (protocol == AetherProtocol.ZERO_TRUST && isZeroTrustCodePrompt(lower)) {
            onCodeRequired()
            return
        }

        ExitDetailRegex.find(line)?.let { match ->
            val ip = match.groupValues.getOrNull(1)?.trim() ?: ""
            val country = match.groupValues.getOrNull(2)?.trim()?.uppercase() ?: ""
            val colo = match.groupValues.getOrNull(3)?.trim() ?: ""
            if (ip.isNotEmpty() && country.length == 2) {
                IpInfoRepository.updateFromExitLine(ip, country, colo)
            }
        }

        if (lower.contains("masque") || lower.contains("warp-in-warp") || lower.contains("wireguard")) {
            MimOuterRegex.find(line)?.let { LastMimHops.outer = it.groupValues[1].trim().trimEnd(',', ';') }
            MimReadyRegex.find(line)?.let {
                LastMimHops.outer = it.groupValues[1].trim().trimEnd(',', ';')
                LastMimHops.inner = it.groupValues[2].trim().trimEnd(',', ';')
            }
        }

        if (lower.contains("tor")) {
            val bootstrapMatch = Regex("""bootstrapp?ed?\s+(\d{1,3})\s*%""").find(lower)
            if (bootstrapMatch != null) {
                bootstrapMatch.groupValues.getOrNull(1)?.toIntOrNull()?.let { TorController.notifyBootstrap(it) }
            }
            if (lower.contains("tor is ready") || lower.contains("leaves through tor") || lower.contains("way out")) {
                TorController.notifyCoreReady()
                TorController.notifyBootstrap(100)
                val readyPort = Regex("""127\.0\.0\.1:(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                TorController.notifySocksListening(readyPort ?: TorController.currentPort())
                TorController.notifyProxyReady(readyPort ?: TorController.currentPort())
            } else if (lower.contains("tor socks5 listening")) {
                val listenPort = Regex("""127\.0\.0\.1:(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                TorController.notifySocksListening(listenPort ?: TorController.currentPort())
            }
        }

        val isCriticalError = (lower.contains("fatal") || lower.contains("panic")) &&
                !lower.contains("socksbridge") &&
                !lower.contains("connection failed")

        val isLost = (_connectionStatus.value == ConnectionStatus.RUNNING && (
            lower.contains("tunnel lost") ||
            lower.contains("broken pipe") ||
            lower.contains("stream closed") ||
            lower.contains("tunnel ended") ||
            lower.contains("tunnel exited") ||
            lower.contains("wireguard tunnel exited") ||
            lower.contains("masque tunnel ended")
        )) || lower.contains("all gateways failed")

        when {
            lower.contains("scanning") -> {
                goolOuterValidated = false
                dataPlaneOk = false
                quickRetryPending.set(false)
                SocksGate.setReady(SocksReadiness.NOT_READY)
                updateState(ConnectionStatus.STARTING, attemptId)
            }
            lower.contains("validating") -> {
                if (_connectionStatus.value == ConnectionStatus.STARTING) updateState(ConnectionStatus.VALIDATING, attemptId)
            }
            lower.contains("tls established") || lower.contains("tls handshake complete") -> {
                updateState(ConnectionStatus.VALIDATING, attemptId)
            }
            lower.contains("connect-ip status: 200") || lower.contains("connect-ip established") -> {
                quickRetryPending.set(false)
                dataPlaneOk = true
                updateState(ConnectionStatus.DATAPLANE_VALIDATED, attemptId)
            }
            protocol == AetherProtocol.GOOL -> {
                when {
                    lower.contains("gool ready") ||
                    lower.contains("gool exit:") ||
                    (lower.contains("inner") && lower.contains("tunnel validated")) ||
                    lower.contains("wiw ready") ||
                    lower.contains("warp-in-warp ready") -> {
                        quickRetryPending.set(false)
                        dataPlaneOk = true
                        updateState(ConnectionStatus.DATAPLANE_VALIDATED, attemptId)
                        updateState(ConnectionStatus.SOCKS_READY, attemptId)
                    }
                    lower.contains("socks") && lower.contains("listening") && lower.contains(lastBindAddress) -> {
                        quickRetryPending.set(false)
                        dataPlaneOk = true
                        updateState(ConnectionStatus.DATAPLANE_VALIDATED, attemptId)
                        updateState(ConnectionStatus.SOCKS_READY, attemptId)
                    }
                    lower.contains("registering the gool") ||
                    lower.contains("validating wireguard tunnel") ||
                    lower.contains("connect-ip status: 200") ||
                    (lower.contains("tunnel validated") && !lower.contains("inner")) -> {
                        goolOuterValidated = true
                        updateState(ConnectionStatus.VALIDATING, attemptId)
                    }
                }
            }
            (protocol != AetherProtocol.GOOL) && (
                lower.contains("tunnel validated") ||
                lower.contains("data-plane verification passed") ||
                lower.contains("data plane verification passed") ||
                lower.contains("connect-ip status: 200") ||
                lower.contains("connect-ip established") ||
                (lower.contains("exit location") && lower.contains("accepted"))
            ) -> {
                quickRetryPending.set(false)
                dataPlaneOk = true
                updateState(ConnectionStatus.DATAPLANE_VALIDATED, attemptId)
            }
            (protocol != AetherProtocol.GOOL) && lower.contains("socks") && lower.contains("listening") -> {
                quickRetryPending.set(false)
                dataPlaneOk = true
                updateState(ConnectionStatus.DATAPLANE_VALIDATED, attemptId)
                updateState(ConnectionStatus.SOCKS_READY, attemptId)
            }
            ((lower.contains("reconnecting") && _connectionStatus.value == ConnectionStatus.RUNNING) || isLost) -> {
                if (isReconnecting.compareAndSet(false, true)) {
                    goolOuterValidated = false
                    dataPlaneOk = false
                    SocksGate.setReady(SocksReadiness.NOT_READY)
                    if ((PsiphonController.isConnected() || TorController.isConnected()) && quickRetryPending.compareAndSet(false, true)) {
                        LogRepository.i("[AetherCore] Cached gateway lost; scheduling single 3s quick retry (chain up)", "AetherCore")
                    }
                    updateState(ConnectionStatus.RECONNECTING, attemptId)
                    scope.launch {
                        delay(100.milliseconds)
                        isReconnecting.set(false)
                    }
                }
            }
            isCriticalError -> {
                val current = _connectionStatus.value
                if (current != ConnectionStatus.RUNNING && current != ConnectionStatus.RECONNECTING) {
                    updateState(ConnectionStatus.ERROR, attemptId)
                }
            }
        }
    }

    private fun updateState(state: ConnectionStatus, attemptId: Long = currentAttemptId.get()) {
        if (currentAttemptId.get() == attemptId) {
            _connectionStatus.value = state
        }
    }

    private fun writeRoutingFile(config: AetherConfig): java.io.File? {
        val rules = config.routingRules
        val block = rules.filter { it.mode == RoutingMode.BLOCK }
        val direct = rules.filter { it.mode == RoutingMode.DIRECT }

        if (block.isEmpty() && direct.isEmpty()) return null

        return try {
            val file = java.io.File(context.filesDir, "routing.ast")
            val content = StringBuilder()

            if (block.isNotEmpty()) {
                content.append("[block]\n")
                block.forEach { content.append(formatRoutingPattern(it.pattern)).append("\n") }
                content.append("\n")
            }

            if (direct.isNotEmpty()) {
                content.append("[direct]\n")
                direct.forEach { content.append(formatRoutingPattern(it.pattern)).append("\n") }
                content.append("\n")
            }

            file.writeText(content.toString())
            file
        } catch (e: Exception) {
            LogRepository.e("Failed to write routing file: ${e.localizedMessage}")
            null
        }
    }

    private fun formatRoutingPattern(pattern: String): String {
        val trimmed = pattern.trim()
        if (trimmed.startsWith("domain:") || trimmed.startsWith("ip:") ||
            trimmed.startsWith("keyword:") || trimmed.startsWith("regexp:") ||
            trimmed == "private") {
            return trimmed
        }

        val isIp = trimmed.all { it.isDigit() || it == '.' || it == ':' || it == '/' || (it.lowercaseChar() in 'a'..'f') } &&
                (trimmed.contains('.') || trimmed.contains(':'))

        return if (isIp) "ip:$trimmed" else "domain:$trimmed"
    }

    fun stop() {
        currentAttemptId.incrementAndGet()
        goolOuterValidated = false
        LastMimHops.clear()
        _connectionStatus.value = ConnectionStatus.STOPPED

        var jobToCancel: Job?
        var procToDestroy: Process?

        synchronized(lock) {
            jobToCancel = runnerJob
            procToDestroy = process
            runnerJob = null
            process = null
        }

        jobToCancel?.cancel()
        try {
            procToDestroy?.destroyForcibly()
        } catch (_: Exception) {}

        LogRepository.i("System core shutdown initiated.")
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
