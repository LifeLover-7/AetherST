package io.github.immaghzbad.aetherst.shared.core

import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.ActiveProxyProvider
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.model.*
import io.github.immaghzbad.aetherst.core.CloakController
import io.github.immaghzbad.aetherst.core.PsiphonController
import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.platform.getTrafficProvider
import io.github.immaghzbad.aetherst.platform.getSystemUtils
import io.github.immaghzbad.aetherst.shared.platform.Bridge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

actual object ConnectionController {
    private val _isWaitingForCode = MutableStateFlow(false)
    actual val isWaitingForCode: StateFlow<Boolean> = _isWaitingForCode.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.STOPPED)
    actual val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    actual val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _sessionTraffic = MutableStateFlow(SessionTraffic())
    actual val sessionTraffic: StateFlow<SessionTraffic> = _sessionTraffic.asStateFlow()

    actual fun markStatus(status: ConnectionStatus) {
        _status.value = status
    }

    @Volatile
    private var INSTANCE: ControllerImpl? = null

    actual fun getInstance(context: PlatformContext) {
        if (INSTANCE == null) {
            synchronized(this) {
                if (INSTANCE == null) INSTANCE = ControllerImpl(context)
            }
        }
    }

    fun submitLoginCode(code: String) {
        _isWaitingForCode.value = false
        INSTANCE?.submitLoginCode(code)
    }

    fun getImpl(context: PlatformContext): ControllerImpl {
        getInstance(context)
        return INSTANCE!!
    }

    class ControllerImpl(private val context: PlatformContext) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val actionMutex = Mutex()
        private val runner = AetherProcessRunner(context)
        private val trafficProvider = getTrafficProvider(context)
        private val loginCodeChannel = Channel<String>(Channel.UNLIMITED)

        private var connectJob: Job? = null
        private var timerJob: Job? = null
        private var baseTx = 0L
        private var baseRx = 0L
        private var prevTx = 0L
        private var prevRx = 0L

        @Volatile private var tunnelModeStarted = false
        @Volatile private var activeTargetPort = 1819
        @Volatile private var isPsiphonActive = false
        @Volatile private var isUserInitiatedStop = false
        @Volatile private var wgExitTunnel: io.github.immaghzbad.aetherst.shared.net.WireGuardExitTunnel? = null
        @Volatile private var customSocksRelay: io.github.immaghzbad.aetherst.shared.net.CustomSocks5Relay? = null
        private var statusJob: Job? = null
        private var watchdogJob: Job? = null
        private var reconnectJob: Job? = null

        init {
            scope.launch {
                Bridge.statusOverride.collect { s ->
                    if (s != null) {
                        _status.value = s
                        if (s == ConnectionStatus.STOPPED || s == ConnectionStatus.ERROR) {
                            stopTimer()
                        }
                    }
                }
            }
            scope.launch {
                Bridge.trafficOverride.collect { if (it != null) _sessionTraffic.value = it }
            }
            scope.launch {
                Bridge.elapsedOverride.collect { if (it != null) _elapsedSeconds.value = it }
            }
        }

        fun start() {
            scope.launch {
                actionMutex.withLock {
                    if (connectJob?.isActive == true ||
                        _status.value == ConnectionStatus.RUNNING ||
                        _status.value == ConnectionStatus.VALIDATING
                    ) return@withLock

                    isUserInitiatedStop = false
                    watchdogJob?.cancel()
                    watchdogJob = null
                    reconnectJob?.cancel()
                    reconnectJob = null
                    doStop()
                    _status.value = ConnectionStatus.STARTING

                    connectJob = scope.launch {
                        try {
                            doStart()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            LogRepository.e("[Controller] Connection error: ${e.message}")
                            doStop()
                            _status.value = ConnectionStatus.ERROR
                        }
                    }
                }
            }
        }

        private suspend fun doStart() {
            LogRepository.i("[Controller] Initializing connection process...", "AetherSystem")

            val config = AetherConfigRepository.getInstance(getSettings(context)).config.value.effectiveZeroTrustConfig()
            var effectiveConfig = config

            // تنظیم بهینه GOOL مطابق معماری مدرن Aether v2.4.0 (تونل WireGuard داخل MASQUE)
            if (effectiveConfig.protocol == AetherProtocol.GOOL) {
                effectiveConfig = effectiveConfig.copy(
                    ipMode = if (effectiveConfig.ipMode == AetherIpMode.AUTO) AetherIpMode.IPV4 else effectiveConfig.ipMode,
                    noise = if (effectiveConfig.noise == AetherNoise.OFF) AetherNoise.BALANCED else effectiveConfig.noise,
                    h2Mode = true,
                    h2Fragment = true,
                    fragmentSize = effectiveConfig.fragmentSize.ifBlank { "30-60" },
                    fragmentDelay = effectiveConfig.fragmentDelay.ifBlank { "5-15" },
                    masqueSni = effectiveConfig.masqueSni.ifBlank { "consumer-masque.cloudflareclient.com" },
                    mtu = effectiveConfig.mtu.coerceIn(1120, 1280),
                    validateSecs = effectiveConfig.validateSecs.coerceAtLeast(15)
                )
            } else if (effectiveConfig.protocol == AetherProtocol.MASQUE || effectiveConfig.protocol == AetherProtocol.MIM) {
                if (effectiveConfig.masqueSni.isBlank()) {
                    effectiveConfig = effectiveConfig.copy(masqueSni = "consumer-masque.cloudflareclient.com")
                }
            } else if (CloakController.isSupported(effectiveConfig)) {
                val started = runCatching { CloakController.start(context, effectiveConfig) }.getOrDefault(false)
                if (started && CloakController.isRunning()) {
                    effectiveConfig = effectiveConfig.copy(peer = CloakController.getEffectivePeer(effectiveConfig))
                    LogRepository.i("[Controller] Cloak active, routing via ${effectiveConfig.peer}", "Cloak")
                }
            }

            baseTx = getWintunBytes().first
            baseRx = getWintunBytes().second

            val coreSocksPort = effectiveConfig.socksPort.toIntOrNull() ?: 1819
            val configuredHttpPort = effectiveConfig.httpPort.toIntOrNull() ?: 1820
            val actualHttpPort = if (configuredHttpPort == coreSocksPort) coreSocksPort + 1 else configuredHttpPort

            statusJob?.cancel()
            statusJob = scope.launch {
                runner.connectionStatus.collect { s ->
                    if (!isUserInitiatedStop) {
                        if (s == ConnectionStatus.ERROR || s == ConnectionStatus.FAILED) {
                            if (_status.value == ConnectionStatus.RUNNING && effectiveConfig.smartReconnect) {
                                LogRepository.w("[Controller] Runner reported $s during active session. Holding network and starting auto-reconnect...", "Watchdog")
                                triggerPersistentReconnect(effectiveConfig, coreSocksPort, actualHttpPort)
                                return@collect
                            }
                        }
                        if (_status.value != ConnectionStatus.STOPPED && s != ConnectionStatus.STOPPED) {
                            if (_status.value != ConnectionStatus.RECONNECTING) {
                                _status.value = s
                            }
                        }
                    }
                }
            }

            runner.start(
                config = effectiveConfig.copy(httpProxyEnabled = true, httpPort = actualHttpPort.toString()),
                bindAddress = "127.0.0.1:$coreSocksPort",
                onCodeRequired = { _isWaitingForCode.value = true },
                inputProvider = { loginCodeChannel.receive() }
            )

            // انتظار واقعی برای متصل شدن هسته با مهلت کافی برای اسکن در شبکه ایران
            var coreReady = false
            val maxWaitLoops = if (effectiveConfig.protocol == AetherProtocol.GOOL) 150 else 120
            for (i in 0..maxWaitLoops) {
                currentCoroutineContext().ensureActive()
                val currentRunnerStatus = runner.connectionStatus.value
                if (currentRunnerStatus == ConnectionStatus.RUNNING || probePort("127.0.0.1", coreSocksPort)) {
                    coreReady = true
                    break
                }
                if (i > 10 && currentRunnerStatus == ConnectionStatus.ERROR) {
                    break
                }
                delay(300.milliseconds)
            }

            if (!coreReady) {
                LogRepository.e("[Controller] Core connection timed out")
                doStop()
                _status.value = ConnectionStatus.ERROR
                return
            }

            var effectiveTargetPort = coreSocksPort
            isPsiphonActive = false
            if (PsiphonController.isSupported(effectiveConfig)) {
                _status.value = ConnectionStatus.VALIDATING
                val upstreamUrl = "http://127.0.0.1:$actualHttpPort"
                LogRepository.i("[Controller] Chaining Psiphon over HTTP proxy $upstreamUrl", "Psiphon")

                val psiphonStarted = PsiphonController.start(
                    context = context,
                    config = effectiveConfig,
                    upstream = upstreamUrl
                )

                if (psiphonStarted) {
                    var psiphonReady = false
                    for (i in 0..30) {
                        currentCoroutineContext().ensureActive()
                        if (PsiphonController.isConnected() || PsiphonController.probeListening()) {
                            psiphonReady = true
                            break
                        }
                        delay(1000.milliseconds)
                    }

                    if (psiphonReady) {
                        effectiveTargetPort = PsiphonController.getPort()
                        isPsiphonActive = true
                        ActiveProxyProvider.psiphonProxyUrl = PsiphonController.getUpstreamProxy()
                        LogRepository.i("[Controller] Psiphon Chain ready! Port: $effectiveTargetPort", "Psiphon")
                    } else {
                        LogRepository.w("[Controller] Psiphon wait timed out, falling back to core port $coreSocksPort", "Psiphon")
                    }
                }
            } else if (effectiveConfig.exitNodeMode == ExitNodeMode.CUSTOM_WG) {
                val wgCfg = effectiveConfig.customWgConfig
                if (wgCfg.isValid()) {
                    val tunnel = io.github.immaghzbad.aetherst.shared.net.WireGuardExitTunnel(
                        config = wgCfg,
                        primarySocksHost = "127.0.0.1",
                        primarySocksPort = coreSocksPort,
                        localListenPort = 10805
                    )
                    tunnel.start()
                    wgExitTunnel = tunnel
                    effectiveTargetPort = 10805
                    LogRepository.i("[Controller] Custom WireGuard Exit ready on port 10805", "WireGuard")
                } else {
                    LogRepository.w("[Controller] Custom WireGuard config invalid, falling back to core port $coreSocksPort", "WireGuard")
                }
            } else if (effectiveConfig.exitNodeMode == ExitNodeMode.CUSTOM_SOCKS5 && effectiveConfig.customSocks5Host.isNotBlank()) {
                val relay = io.github.immaghzbad.aetherst.shared.net.CustomSocks5Relay(
                    listenPort = 10806,
                    primarySocksHost = "127.0.0.1",
                    primarySocksPort = coreSocksPort,
                    remoteHost = effectiveConfig.customSocks5Host.trim(),
                    remotePort = effectiveConfig.customSocks5Port,
                    username = effectiveConfig.customSocks5Username,
                    password = effectiveConfig.customSocks5Password
                )
                relay.start()
                customSocksRelay = relay
                effectiveTargetPort = 10806
                LogRepository.i("[Controller] Custom SOCKS5 Exit ready on port 10806", "SOCKS5")
            }

            currentCoroutineContext().ensureActive()
            activeTargetPort = effectiveTargetPort

            if (effectiveConfig.connectionMode == ConnectionMode.TUNNEL) {
                startTunnelMode(effectiveConfig, effectiveTargetPort)
            } else if (effectiveConfig.connectionMode == ConnectionMode.SYSTEM_PROXY) {
                getSystemUtils(context).setSystemProxy("127.0.0.1", effectiveTargetPort)
            }

            _status.value = ConnectionStatus.RUNNING
            startTimer()
            startWatchdog(effectiveConfig, coreSocksPort, actualHttpPort)
        }

        private fun startWatchdog(effectiveConfig: AetherConfig, coreSocksPort: Int, actualHttpPort: Int) {
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                while (isActive && !isUserInitiatedStop) {
                    delay(4000.milliseconds)
                    if (_status.value == ConnectionStatus.RUNNING && !isUserInitiatedStop) {
                        val portOk = probePort("127.0.0.1", coreSocksPort)
                        if (!portOk) {
                            if (effectiveConfig.smartReconnect && !isUserInitiatedStop) {
                                LogRepository.w("[Controller] Core port $coreSocksPort probe failed (network drop / mobile hotspot interrupt). Holding TUN interface...", "Watchdog")
                                triggerPersistentReconnect(effectiveConfig, coreSocksPort, actualHttpPort)
                            }
                        }
                    }
                }
            }
        }

        private fun triggerPersistentReconnect(effectiveConfig: AetherConfig, coreSocksPort: Int, actualHttpPort: Int) {
            if (reconnectJob?.isActive == true || isUserInitiatedStop) return
            _status.value = ConnectionStatus.RECONNECTING
            LogRepository.i("[Controller] State -> RECONNECTING. Keeping Wintun interface & routes intact to prevent app disconnects.", "Watchdog")

            reconnectJob = scope.launch {
                var attempt = 0
                val maxAttempts = effectiveConfig.reconnectRetryLimit.coerceIn(5, 60)
                while (isActive && !isUserInitiatedStop && attempt < maxAttempts) {
                    attempt++
                    LogRepository.i("[Controller] Hold & Reconnect attempt $attempt of $maxAttempts...", "Watchdog")

                    // Gently stop runner process only (do NOT stop TunHelper / Wintun!)
                    try { runner.stop() } catch (_: Throwable) {}
                    delay(1500.milliseconds)

                    if (isUserInitiatedStop) break

                    val hasPhysicalRoute = canReachGatewayOrInternet()
                    if (!hasPhysicalRoute) {
                        LogRepository.i("[Controller] Physical internet / mobile hotspot link unavailable (e.g. phone call). Holding...", "Watchdog")
                        delay(2500.milliseconds)
                        continue
                    }

                    try {
                        runner.start(
                            config = effectiveConfig.copy(httpProxyEnabled = true, httpPort = actualHttpPort.toString()),
                            bindAddress = "127.0.0.1:$coreSocksPort",
                            onCodeRequired = { _isWaitingForCode.value = true },
                            inputProvider = { loginCodeChannel.receive() }
                        )

                        var ready = false
                        for (w in 0..20) {
                            if (isUserInitiatedStop) break
                            if (probePort("127.0.0.1", coreSocksPort)) {
                                ready = true
                                break
                            }
                            delay(500.milliseconds)
                        }

                        if (ready) {
                            if (effectiveConfig.exitNodeMode == ExitNodeMode.CUSTOM_WG && wgExitTunnel == null) {
                                try {
                                    val wgCfg = effectiveConfig.customWgConfig
                                    if (wgCfg.isValid()) {
                                        val tunnel = io.github.immaghzbad.aetherst.shared.net.WireGuardExitTunnel(
                                            config = wgCfg,
                                            primarySocksHost = "127.0.0.1",
                                            primarySocksPort = coreSocksPort,
                                            localListenPort = 10805
                                        )
                                        tunnel.start()
                                        wgExitTunnel = tunnel
                                    }
                                } catch (_: Throwable) {}
                            } else if (effectiveConfig.exitNodeMode == ExitNodeMode.CUSTOM_SOCKS5 && customSocksRelay == null) {
                                try {
                                    val relay = io.github.immaghzbad.aetherst.shared.net.CustomSocks5Relay(
                                        listenPort = 10806,
                                        primarySocksHost = "127.0.0.1",
                                        primarySocksPort = coreSocksPort,
                                        remoteHost = effectiveConfig.customSocks5Host.trim(),
                                        remotePort = effectiveConfig.customSocks5Port,
                                        username = effectiveConfig.customSocks5Username,
                                        password = effectiveConfig.customSocks5Password
                                    )
                                    relay.start()
                                    customSocksRelay = relay
                                } catch (_: Throwable) {}
                            }

                            _status.value = ConnectionStatus.RUNNING
                            LogRepository.i("[Controller] Resilient reconnect SUCCESSFUL! Session restored without dropping application sockets.", "Watchdog")
                            return@launch
                        }
                    } catch (e: Exception) {
                        LogRepository.w("[Controller] Reconnect attempt failed: ${e.message}", "Watchdog")
                    }

                    delay(2000.milliseconds)
                }

                if (!isUserInitiatedStop && _status.value == ConnectionStatus.RECONNECTING) {
                    LogRepository.e("[Controller] Persistent reconnect exhausted after $maxAttempts attempts.", "Watchdog")
                    _status.value = ConnectionStatus.ERROR
                    doStop()
                }
            }
        }

        private suspend fun canReachGatewayOrInternet(): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use {
                    it.connect(InetSocketAddress("1.1.1.1", 53), 1500)
                    true
                }
            }.getOrElse {
                runCatching {
                    Socket().use {
                        it.connect(InetSocketAddress("8.8.8.8", 53), 1500)
                        true
                    }
                }.getOrDefault(false)
            }
        }

        private fun startTunnelMode(config: AetherConfig, targetPort: Int) {
            if (tunnelModeStarted) return
            tunnelModeStarted = true
            val effectiveMtu = 1280.coerceAtMost(config.mtu.coerceIn(576, 9000))
            TunHelper.start(
                socksTargetPort = targetPort,
                configuredMtu = effectiveMtu,
                bypassExes = config.excludedPackages,
                blockedExes = config.blockedPackages,
                tunnelAllApps = config.tunnelAllApps
            )
            LogRepository.i("[Controller] TUN helper started on port $targetPort, mtu=$effectiveMtu, bypass=${config.excludedPackages.size}, blocked=${config.blockedPackages.size}")
        }

        fun stop() {
            scope.launch {
                actionMutex.withLock {
                    isUserInitiatedStop = true
                    watchdogJob?.cancel()
                    watchdogJob = null
                    reconnectJob?.cancel()
                    reconnectJob = null

                    if (_status.value == ConnectionStatus.STOPPED && connectJob?.isActive != true) return@withLock
                    _status.value = ConnectionStatus.STOPPED
                    _isWaitingForCode.value = false
                    stopTimer()
                    connectJob?.cancelAndJoin()
                    connectJob = null
                    doStop()
                }
            }
        }

        private fun doStop() {
            LogRepository.i("[Controller] Stopping all components")
            watchdogJob?.cancel()
            watchdogJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            statusJob?.cancel()
            statusJob = null

            try { PsiphonController.stop() } catch (_: Throwable) {}
            try { wgExitTunnel?.stop() } catch (_: Throwable) {}
            wgExitTunnel = null
            try { customSocksRelay?.stop() } catch (_: Throwable) {}
            customSocksRelay = null
            try { runner.stop() } catch (_: Throwable) {}
            try { CloakController.stop() } catch (_: Throwable) {}

            ActiveProxyProvider.psiphonProxyUrl = null
            isPsiphonActive = false

            try { getSystemUtils(context).clearSystemProxy() } catch (_: Throwable) {}
            tunnelModeStarted = false
            try { TunHelper.stop() } catch (_: Throwable) {}
        }

        private suspend fun probePort(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use {
                    it.connect(InetSocketAddress(host, port), 500)
                    true
                }
            }.getOrDefault(false)
        }

        private fun startTimer() {
            timerJob?.cancel()
            _elapsedSeconds.value = 0
            prevTx = 0L
            prevRx = 0L
            timerJob = scope.launch {
                var seconds = 0L
                while (isActive) {
                    delay(1000.milliseconds)
                    seconds++
                    _elapsedSeconds.value = seconds
                    updateTraffic()
                }
            }
        }

        private fun stopTimer() {
            timerJob?.cancel()
            _elapsedSeconds.value = 0L
            _sessionTraffic.value = SessionTraffic()
        }

        private fun getWintunBytes(): Pair<Long, Long> {
            return try {
                val proc = ProcessBuilder("netsh", "interface", "ipv4", "show", "subinterfaces", "AetherST")
                    .redirectErrorStream(true).start()
                val lines = proc.inputStream.bufferedReader().readLines()
                var rx = 0L
                var tx = 0L
                for (line in lines) {
                    if (line.contains("AetherST", ignoreCase = true)) {
                        val toks = line.trim().split(Regex("\\s+"))
                        if (toks.size >= 5) {
                            rx = toks[2].toLongOrNull() ?: 0L
                            tx = toks[3].toLongOrNull() ?: 0L
                            break
                        }
                    }
                }
                Pair(tx, rx)
            } catch (_: Exception) {
                Pair(trafficProvider.getTxBytes(), trafficProvider.getRxBytes())
            }
        }

        private fun updateTraffic() {
            val (currentTx, currentRx) = getWintunBytes()
            val totalTx = (currentTx - baseTx).coerceAtLeast(0L)
            val totalRx = (currentRx - baseRx).coerceAtLeast(0L)

            val uploadSpeed = (totalTx - prevTx).coerceAtLeast(0L).toDouble()
            val downloadSpeed = (totalRx - prevRx).coerceAtLeast(0L).toDouble()
            prevTx = totalTx
            prevRx = totalRx

            _sessionTraffic.value = SessionTraffic(
                uploadedBytes = totalTx,
                downloadedBytes = totalRx,
                uploadSpeedBps = uploadSpeed,
                downloadSpeedBps = downloadSpeed
            )
        }

        fun submitLoginCode(code: String) {
            _isWaitingForCode.value = false
            loginCodeChannel.trySend(code)
        }
    }
}
