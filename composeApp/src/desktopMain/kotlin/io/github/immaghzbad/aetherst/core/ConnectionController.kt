package io.github.immaghzbad.aetherst.shared.core

import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
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
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val actionMutex = Mutex()
        private val runner = AetherProcessRunner(context)
        private val trafficProvider = getTrafficProvider(context)
        private val loginCodeChannel = Channel<String>(Channel.UNLIMITED)

        private var timerJob: Job? = null
        private var baseTx = 0L
        private var baseRx = 0L
        private var prevTx = 0L
        private var prevRx = 0L
        @Volatile private var socksProxy: LocalSocksProxyServer? = null
        @Volatile private var httpProxy: LocalHttpProxyServer? = null
        @Volatile private var dnsServer: LocalDnsServer? = null
        @Volatile private var tunnelModeStarted = false
        @Volatile private var activeTargetPort = 1819
        private var routingEngine: RoutingEngine? = null
        private var statusJob: Job? = null
        private var modeJob: Job? = null

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
                Bridge.trafficOverride.collect {
                    if (it != null) _sessionTraffic.value = it
                }
            }
            scope.launch {
                Bridge.elapsedOverride.collect {
                    if (it != null) _elapsedSeconds.value = it
                }
            }
        }

        fun start() {
            _status.value = ConnectionStatus.STARTING
            scope.launch {
                actionMutex.withLock {
                    withContext(Dispatchers.IO) {
                        doStart()
                    }
                }
            }
        }

        private fun doStart() {
            LogRepository.i("Initializing connection process...", "AetherSystem")
            if (statusJob == null) {
                statusJob = scope.launch {
                    runner.connectionStatus.collect { _status.value = it }
                }
            }
            val config = AetherConfigRepository.getInstance(getSettings(context)).config.value.effectiveZeroTrustConfig()
            var effectiveConfig = config

            try {
                if (CloakController.isSupported(config)) {
                    val started = try { CloakController.start(context, config) } catch (_: Throwable) { false }
                    if (started && CloakController.isRunning()) {
                        effectiveConfig = config.copy(peer = CloakController.getEffectivePeer(config))
                        LogRepository.i("[Controller] Cloak active, routing MASQUE via ${effectiveConfig.peer}", "Cloak")
                    } else if (started) {
                        LogRepository.w("[Controller] Cloak start reported success but not running, fallback to direct peer", "Cloak")
                    }
                }
            } catch (_: Throwable) {}

            baseTx = trafficProvider.getTxBytes()
            baseRx = trafficProvider.getRxBytes()

            try { getSystemUtils(context).clearSystemProxy() } catch (_: Throwable) {}

            val coreSocksPort = effectiveConfig.socksPort.toIntOrNull() ?: 1819

            // ۱. استارت هسته اصلی Aether روی پورت لوکال (مثلاً 1819)
            runner.start(
                config = effectiveConfig,
                bindAddress = "127.0.0.1:$coreSocksPort",
                onCodeRequired = { _isWaitingForCode.value = true },
                inputProvider = { loginCodeChannel.receive() }
            )

            // ۲. اگر زنجیره‌سازی سایفون فعال است، سایفون را از داخل تونل Aether عبور بده
            var effectiveTargetPort = coreSocksPort
            try {
                if (PsiphonController.isSupported(effectiveConfig)) {
                    val psiphonStarted = PsiphonController.start(
                        context = context,
                        config = effectiveConfig,
                        upstream = "socks5://127.0.0.1:$coreSocksPort"
                    )
                    if (psiphonStarted) {
                        effectiveTargetPort = PsiphonController.getPort()
                        LogRepository.i("[Controller] Psiphon Chain active: Windows(10808) -> Psiphon($effectiveTargetPort) -> Aether($coreSocksPort)", "Psiphon")
                    } else {
                        LogRepository.w("[Controller] Psiphon failed to start, using direct Aether port $coreSocksPort", "Psiphon")
                    }
                }
            } catch (t: Throwable) {
                LogRepository.e("[Controller] Error starting Psiphon chain: ${t.message}", "Psiphon")
            }
            activeTargetPort = effectiveTargetPort

            // ۳. هدایت پراکسی‌های شمارنده ویندوز به پورت نهایی (سایفون یا Aether)
            routingEngine = RoutingEngine(effectiveConfig.routingRules)
            socksProxy = LocalSocksProxyServer(
                listenHost = "127.0.0.1",
                listenPort = 10808,
                targetHost = "127.0.0.1",
                targetPort = effectiveTargetPort,
                routingEngine = routingEngine!!
            ).apply { start() }
            httpProxy = LocalHttpProxyServer(
                listenHost = "127.0.0.1",
                listenPort = 10809,
                targetHost = "127.0.0.1",
                targetPort = effectiveTargetPort,
                routingEngine = routingEngine!!
            ).apply { start() }
            LogRepository.i("[Controller] Counting proxies started (socks=10808, http=10809) -> target $effectiveTargetPort")

            if (effectiveConfig.connectionMode == ConnectionMode.TUNNEL || effectiveConfig.connectionMode == ConnectionMode.SYSTEM_PROXY) {
                val dnsUpstream = (if (effectiveConfig.dnsEnabled) effectiveConfig.dnsList else "").ifEmpty { "1.1.1.1,1.0.0.1" }
                val tmpDns = LocalDnsServer(listenHost = "127.0.0.1", listenPort = 53, socksHost = "127.0.0.1", socksPort = effectiveTargetPort, upstreamList = dnsUpstream)
                tmpDns.start()
                if (tmpDns.isRunning()) {
                    dnsServer = tmpDns
                    try { getSystemUtils(context).setSystemDns("127.0.0.1") } catch (_: Throwable) {}
                } else {
                    LogRepository.w("[Controller] DNS relay failed to bind 53 (needs admin), using system DNS $dnsUpstream without relay")
                    try { getSystemUtils(context).setSystemDns(dnsUpstream) } catch (_: Throwable) {}
                }
            }

            modeJob?.cancel()
            if (effectiveConfig.connectionMode == ConnectionMode.TUNNEL) {
                modeJob = scope.launch(Dispatchers.IO) {
                    status.collect { s ->
                        if (s == ConnectionStatus.RUNNING) {
                            delay(500.milliseconds)
                            startTunnelMode(effectiveConfig)
                        }
                    }
                }
            } else if (effectiveConfig.connectionMode == ConnectionMode.SYSTEM_PROXY) {
                modeJob = scope.launch(Dispatchers.IO) {
                    status.collect { s ->
                        if (s == ConnectionStatus.RUNNING) {
                            delay(500.milliseconds)
                            getSystemUtils(context).setSystemProxy("127.0.0.1", 10809)
                        }
                    }
                }
            }

            startTimer()
        }

        private fun startTunnelMode(config: AetherConfig) {
            if (tunnelModeStarted) return
            tunnelModeStarted = true

            if (socksProxy == null) {
                if (routingEngine == null) routingEngine = RoutingEngine(config.routingRules)
                socksProxy = LocalSocksProxyServer(
                    listenHost = "127.0.0.1",
                    listenPort = 10808,
                    targetHost = "127.0.0.1",
                    targetPort = activeTargetPort,
                    routingEngine = routingEngine!!
                ).apply { start() }
            }
            LogRepository.i("[Controller] Local SOCKS bridge listening on 127.0.0.1:10808 -> $activeTargetPort")

            TunHelper.start(activeTargetPort, config.mtu)
            LogRepository.i("[Controller] TUN helper started mtu=${config.mtu}")
        }

        fun stop() {
            _status.value = ConnectionStatus.STOPPED
            stopTimer()
            scope.launch {
                actionMutex.withLock {
                    withContext(Dispatchers.IO) {
                        doStop()
                    }
                }
            }
        }

        private fun doStop() {
            try { PsiphonController.stop() } catch (_: Throwable) {}
            runner.stop()
            try { CloakController.stop() } catch (_: Throwable) {}
            modeJob?.cancel()
            modeJob = null
            try { getSystemUtils(context).clearSystemProxy() } catch (_: Throwable) {}
            try { getSystemUtils(context).clearSystemDns() } catch (_: Throwable) {}
            dnsServer?.stop()
            dnsServer = null
            socksProxy?.stop()
            socksProxy = null
            httpProxy?.stop()
            httpProxy = null
            tunnelModeStarted = false
            TunHelper.stop()
            routingEngine = null
            statusJob?.cancel()
            statusJob = null
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

        private fun updateTraffic() {
            val socksStats = socksProxy?.getStats()
            val httpStats = httpProxy?.getStats()
            val hasCounting = socksStats != null || httpStats != null
            if (hasCounting) {
                val totalTx = (socksStats?.txBytes ?: 0L) + (httpStats?.txBytes ?: 0L)
                val totalRx = (socksStats?.rxBytes ?: 0L) + (httpStats?.rxBytes ?: 0L)
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
                return
            }
            val currentTx = trafficProvider.getTxBytes()
            val currentRx = trafficProvider.getRxBytes()
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
