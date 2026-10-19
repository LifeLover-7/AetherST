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
                    if (_status.value == ConnectionStatus.RUNNING || _status.value == ConnectionStatus.VALIDATING) return@withLock
                    _status.value = ConnectionStatus.STARTING
                    doStart()
                }
            }
        }

        private suspend fun doStart() {
            LogRepository.i("[Controller] Initializing connection process...", "AetherSystem")

            val config = AetherConfigRepository.getInstance(getSettings(context)).config.value.effectiveZeroTrustConfig()
            var effectiveConfig = config

            if (CloakController.isSupported(config)) {
                val started = runCatching { CloakController.start(context, config) }.getOrDefault(false)
                if (started && CloakController.isRunning()) {
                    effectiveConfig = config.copy(peer = CloakController.getEffectivePeer(config))
                    LogRepository.i("[Controller] Cloak active, routing via ${effectiveConfig.peer}", "Cloak")
                }
            }

            baseTx = trafficProvider.getTxBytes()
            baseRx = trafficProvider.getRxBytes()

            val coreSocksPort = effectiveConfig.socksPort.toIntOrNull() ?: 1819
            val httpPort = effectiveConfig.httpPort.toIntOrNull() ?: 1820

            statusJob?.cancel()
            statusJob = scope.launch {
                runner.connectionStatus.collect { s ->
                    if (_status.value != ConnectionStatus.STOPPED && s != ConnectionStatus.STOPPED) {
                        _status.value = s
                    }
                }
            }

            // ۱. استارت هسته با باز بودن پورت HTTP برای سایفون
            runner.start(
                config = effectiveConfig.copy(httpProxyEnabled = true),
                bindAddress = "127.0.0.1:$coreSocksPort",
                onCodeRequired = { _isWaitingForCode.value = true },
                inputProvider = { loginCodeChannel.receive() }
            )

            // ۲. انتظار برای آماده شدن هسته اصلی
            var coreReady = false
            for (i in 0..40) {
                if (runner.connectionStatus.value == ConnectionStatus.RUNNING || probePort("127.0.0.1", coreSocksPort)) {
                    coreReady = true
                    break
                }
                delay(300.milliseconds)
            }

            // ۳. اگر سایفون روشن است، پورت Upstream دقیقاً پورت فعال HTTP باشد
            var effectiveTargetPort = coreSocksPort
            if (PsiphonController.isSupported(effectiveConfig)) {
                _status.value = ConnectionStatus.VALIDATING
                val actualHttpPort = if (probePort("127.0.0.1", httpPort)) httpPort else (if (probePort("127.0.0.1", 1820)) 1820 else httpPort)
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
                        if (PsiphonController.isConnected() || PsiphonController.probeListening()) {
                            psiphonReady = true
                            break
                        }
                        delay(1000.milliseconds)
                    }

                    if (psiphonReady) {
                        effectiveTargetPort = PsiphonController.getPort()
                        LogRepository.i("[Controller] Psiphon Chain ready! Port: $effectiveTargetPort", "Psiphon")
                    } else {
                        LogRepository.w("[Controller] Psiphon timed out, falling back to core port $coreSocksPort", "Psiphon")
                    }
                }
            }

            activeTargetPort = effectiveTargetPort

            // ۴. راه‌اندازی پل پراکسی محلی
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

            // ۵. استارت حالت تونل با MTU امن
            if (effectiveConfig.connectionMode == ConnectionMode.TUNNEL) {
                startTunnelMode(effectiveConfig, effectiveTargetPort)
            } else if (effectiveConfig.connectionMode == ConnectionMode.SYSTEM_PROXY) {
                getSystemUtils(context).setSystemProxy("127.0.0.1", 10809)
            }

            _status.value = ConnectionStatus.RUNNING
            startTimer()
        }

        private fun startTunnelMode(config: AetherConfig, targetPort: Int) {
            if (tunnelModeStarted) return
            tunnelModeStarted = true
            TunHelper.start(targetPort, config.mtu)
            LogRepository.i("[Controller] TUN helper started pointing to $targetPort")
        }

        fun stop() {
            scope.launch {
                actionMutex.withLock {
                    if (_status.value == ConnectionStatus.STOPPED) return@withLock
                    _status.value = ConnectionStatus.STOPPED
                    stopTimer()
                    doStop()
                }
            }
        }

        private fun doStop() {
            LogRepository.i("[Controller] Stopping all components")
            statusJob?.cancel()
            statusJob = null

            try { PsiphonController.stop() } catch (_: Throwable) {}
            runner.stop()
            try { CloakController.stop() } catch (_: Throwable) {}

            try { getSystemUtils(context).clearSystemProxy() } catch (_: Throwable) {}
            dnsServer?.stop()
            dnsServer = null
            socksProxy?.stop()
            socksProxy = null
            httpProxy?.stop()
            httpProxy = null
            tunnelModeStarted = false
            TunHelper.stop()
            routingEngine = null
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

        private fun updateTraffic() {
            val socksStats = socksProxy?.getStats()
            val httpStats = httpProxy?.getStats()
            if (socksStats != null || httpStats != null) {
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
