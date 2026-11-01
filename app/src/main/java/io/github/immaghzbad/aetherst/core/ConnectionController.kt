package io.github.immaghzbad.aetherst.core

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.ActiveProxyProvider
import io.github.immaghzbad.aetherst.shared.data.IpInfoRepository
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.model.SessionTraffic
import io.github.immaghzbad.aetherst.shared.model.*
import io.github.immaghzbad.aetherst.shared.platform.Bridge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object SocksGate {
    private val _readiness = MutableStateFlow(SocksReadiness.NOT_READY)
    val readiness: StateFlow<SocksReadiness> = _readiness.asStateFlow()
    fun setReady(v: SocksReadiness) { _readiness.value = v }
}

interface ConnectionControl {
    suspend fun start()
    suspend fun stop()
    fun submitLoginCode(code: String)
}

class ConnectionController private constructor(context: Context) : ConnectionControl {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = AetherProcessRunner(appContext)
    private val mutex = Mutex()
    private val activeAttemptId = AtomicLong(0)
    private val loginCodeChannel = Channel<String>(Channel.CONFLATED)

    private var timerJob: Job? = null
    private var durationSeconds = 0L
    private var baseTx = 0L
    private var baseRx = 0L
    private var accumulatedTx = 0L
    private var accumulatedRx = 0L
    private var lastEngineTx = 0L
    private var lastEngineRx = 0L
    private var prevTotalTx = 0L
    private var prevTotalRx = 0L
    private var hasManualTraffic = false
    private var useTrafficStatsFallback = true

    companion object {
        const val ACTION_STATUS_CHANGED = "io.github.immaghzbad.aetherst.STATUS_CHANGED"

        @Volatile
        private var INSTANCE: ConnectionController? = null

        private val _status = MutableStateFlow(ConnectionStatus.STOPPED)
        val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

        @Volatile
        var lastKnownStatus: ConnectionStatus = ConnectionStatus.STOPPED
            private set

        @Volatile
        var psiphonChaining: Boolean = false
            private set

        @Volatile
        var torChaining: Boolean = false
            private set

        @Volatile
        var mimGuardActive: Boolean = false
            private set

        private val _isWaitingForCode = MutableStateFlow(false)
        val isWaitingForCode: StateFlow<Boolean> = _isWaitingForCode.asStateFlow()

        private fun notifyStatusChanged(context: Context, newStatus: ConnectionStatus) {
            lastKnownStatus = newStatus
            _status.value = newStatus
            Bridge.statusOverride.value = newStatus
            val intent = Intent(ACTION_STATUS_CHANGED)
            intent.putExtra("status", newStatus.name)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        }

        fun getInstance(context: Context): ConnectionController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionController(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun updateIsWaitingForCode(waiting: Boolean) {
            _isWaitingForCode.value = waiting
            Bridge.isWaitingForCode.value = waiting
        }
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            runner.connectionStatus.drop(1).collect { coreStatus ->
                handleCoreStatus(coreStatus)
            }
        }
    }

    override fun submitLoginCode(code: String) {
        updateIsWaitingForCode(false)
        loginCodeChannel.trySend(code)
    }

    fun markReconnecting() {
        if (_status.value == ConnectionStatus.RUNNING || _status.value == ConnectionStatus.TUN_ACTIVE) {
            notifyStatusChanged(appContext, ConnectionStatus.RECONNECTING)
        }
    }

    override suspend fun start() {
        val attemptId: Long
        mutex.withLock {
            if (_status.value == ConnectionStatus.RUNNING || _status.value == ConnectionStatus.VALIDATING) return
            attemptId = System.currentTimeMillis()
            activeAttemptId.set(attemptId)
            notifyStatusChanged(appContext, ConnectionStatus.STARTING)
        }
        try {
            // پاک‌سازی فایل‌های کش قدیمی در شروع اتصال تا بار اول روی سرور مرده قبلی گیر نکند
            clearLastconn()

            val config = AetherConfigRepository.getInstance(getSettings(PlatformContext(appContext))).config.value.effectiveZeroTrustConfig()
            var effectiveConfig = config

            if (effectiveConfig.protocol == AetherProtocol.GOOL) {
                effectiveConfig = effectiveConfig.copy(
                    wiwScan = true,
                    ipMode = if (effectiveConfig.ipMode == AetherIpMode.AUTO) AetherIpMode.IPV4 else effectiveConfig.ipMode,
                    scanMode = if (effectiveConfig.scanMode == AetherScanMode.TURBO) AetherScanMode.BALANCED else effectiveConfig.scanMode,
                    noise = if (effectiveConfig.noise == AetherNoise.OFF) AetherNoise.GFW else effectiveConfig.noise,
                    mtu = effectiveConfig.mtu.coerceIn(1120, 1240),
                    validateSecs = effectiveConfig.validateSecs.coerceAtLeast(15)
                )
            } else if (CloakController.isSupported(effectiveConfig)) {
                val cloakStarted = runNativeBounded(30000L, "Cloak.start") { CloakController.start(appContext, effectiveConfig) } == true
                if (cloakStarted && CloakController.isRunning()) {
                    effectiveConfig = effectiveConfig.copy(peer = CloakController.getEffectivePeer(effectiveConfig))
                    LogRepository.i("[Controller] Cloak active, routing MASQUE via ${effectiveConfig.peer}")
                }
            }

            val psiphonSupported = PsiphonController.isSupported(effectiveConfig) && effectiveConfig.chainProvider == ChainProvider.PSIPHON
            val torSupported = TorController.isSupported(effectiveConfig) && effectiveConfig.chainProvider == ChainProvider.TOR

            // اطمینان از روشن بودن پورت HTTP هسته هنگام فعال بودن زنجیره سایفون
            if (psiphonSupported && !effectiveConfig.psiphonOnly) {
                val coreSocks = effectiveConfig.socksPort.toIntOrNull() ?: 1819
                val cfgHttp = effectiveConfig.httpPort.toIntOrNull() ?: 1820
                val safeHttpPort = if (cfgHttp == coreSocks) (coreSocks + 1).toString() else effectiveConfig.httpPort.ifBlank { "1820" }
                effectiveConfig = effectiveConfig.copy(httpProxyEnabled = true, httpPort = safeHttpPort)
            }

            val bindHost = if (effectiveConfig.shareHotspot) "0.0.0.0" else "127.0.0.1"
            val bindAddress = "$bindHost:${effectiveConfig.socksPort}"

            hasManualTraffic = false
            accumulatedTx = 0L
            accumulatedRx = 0L
            lastEngineTx = 0L
            lastEngineRx = 0L
            prevTotalTx = 0L
            prevTotalRx = 0L
            useTrafficStatsFallback = effectiveConfig.connectionMode != ConnectionMode.TUNNEL
            val rawTx = TrafficStats.getUidTxBytes(Process.myUid())
            val rawRx = TrafficStats.getUidRxBytes(Process.myUid())
            baseTx = if (rawTx == TrafficStats.UNSUPPORTED.toLong() || rawTx < 0) 0L else rawTx
            baseRx = if (rawRx == TrafficStats.UNSUPPORTED.toLong() || rawRx < 0) 0L else rawRx

            if (torSupported) {
                torChaining = true
                startTorChain(effectiveConfig, bindAddress, attemptId)
                return
            }
            if (psiphonSupported) {
                psiphonChaining = true
                if (effectiveConfig.psiphonOnly) {
                    startPsiphonOnly(effectiveConfig, attemptId)
                    return
                }
                // در تمام پروتکل‌ها (WG / GOOL / MASQUE) ابتدا هسته مستقیم وصل شود و سپس سایفون از طریق HTTP Proxy روی آن زنجیره شود
                LogRepository.i("[Controller] Starting core first for Psiphon chain (${effectiveConfig.protocol})")
                if (!startAetherInternal(effectiveConfig, bindAddress, attemptId)) {
                    throw IllegalStateException("Core failed direct ${effectiveConfig.protocol}")
                }
                if (status.value == ConnectionStatus.RUNNING) {
                    notifyStatusChanged(appContext, ConnectionStatus.VALIDATING)
                }
                val httpUpstream = "http://127.0.0.1:${effectiveConfig.httpPort}"
                LogRepository.i("[Controller] Core ready, chaining Psiphon via $httpUpstream")
                runNativeBounded<Unit>(30000L, "Psiphon.start") { PsiphonController.start(appContext, effectiveConfig, upstream = httpUpstream) }
                if (PsiphonController.isRunning() && awaitPsiphonStable()) {
                    ActiveProxyProvider.psiphonProxyUrl = PsiphonController.getUpstreamProxy()
                    LogRepository.i("[Controller] Psiphon over ${effectiveConfig.protocol} ready via ${ActiveProxyProvider.psiphonProxyUrl}")
                    try {
                        val intent = Intent().setClassName(appContext.packageName, "io.github.immaghzbad.aetherst.service.AetherVpnService").apply {
                            action = "io.github.immaghzbad.aetherst.SWITCH_HEV"
                            putExtra("host", "127.0.0.1")
                            putExtra("port", 3080)
                        }
                        appContext.startService(intent)
                    } catch (_: Exception) {}
                    psiphonChaining = false
                    notifyStatusChanged(appContext, ConnectionStatus.RUNNING)
                    return
                } else {
                    LogRepository.e("[Controller] Psiphon failed to connect over ${effectiveConfig.protocol} - aborting")
                    PsiphonController.stop()
                    ActiveProxyProvider.psiphonProxyUrl = null
                    psiphonChaining = false
                    runCatching { runner.stop() }
                    throw IllegalStateException("Psiphon not connected over ${effectiveConfig.protocol}")
                }
            } else {
                ActiveProxyProvider.psiphonProxyUrl = null
                ActiveProxyProvider.torProxyUrl = null
                if (!startAetherInternal(effectiveConfig, bindAddress, attemptId)) {
                    throw IllegalStateException("Core failed direct")
                }
            }
        } catch (e: Exception) {
            val st = _status.value
            if (st == ConnectionStatus.STOPPED || st == ConnectionStatus.STOPPING) {
                runCatching { cleanup(attemptId) }
                return
            }
            if (runner.connectionStatus.value != ConnectionStatus.RUNNING) {
                LogRepository.e("[Controller] Startup failed: ${e.localizedMessage}")
                cleanup(attemptId)
                notifyStatusChanged(appContext, ConnectionStatus.ERROR)
            }
        }
    }

    private suspend fun startPsiphonOnly(config: AetherConfig, attemptId: Long) {
        runNativeBounded<Unit>(30000L, "Psiphon.start") { PsiphonController.start(appContext, config, upstream = null) }
        if (!PsiphonController.isRunning()) abortPsiphonOnly("Psiphon failed to start")
        if (!awaitPsiphonStable()) abortPsiphonOnly("Psiphon not connected/stable")
        if (activeAttemptId.get() != attemptId) {
            ActiveProxyProvider.psiphonProxyUrl = null
            PsiphonController.stop()
            psiphonChaining = false
            return
        }
        ActiveProxyProvider.psiphonProxyUrl = PsiphonController.getUpstreamProxy()
        val onlyPort = ActiveProxyProvider.psiphonProxyUrl?.substringAfterLast(":")?.toIntOrNull() ?: 3080
        try {
            val intent = Intent().setClassName(appContext.packageName, "io.github.immaghzbad.aetherst.service.AetherVpnService").apply {
                action = "io.github.immaghzbad.aetherst.SWITCH_HEV"
                putExtra("host", "127.0.0.1")
                putExtra("port", onlyPort)
            }
            appContext.startService(intent)
        } catch (_: Exception) {}
        notifyStatusChanged(appContext, ConnectionStatus.RUNNING)
        startTimer()
        psiphonChaining = false
    }

    private suspend fun abortPsiphonOnly(reason: String): Nothing {
        ActiveProxyProvider.psiphonProxyUrl = null
        PsiphonController.stop()
        psiphonChaining = false
        runCatching { runner.stop() }
        throw IllegalStateException(reason)
    }

    private suspend fun startTorChain(config: AetherConfig, bindAddress: String, attemptId: Long) {
        if (config.torMode == TorMode.TOR_ONLY) {
            startTorOnly(config, bindAddress, attemptId)
            return
        }
        if (config.torMode == TorMode.TOR_REVERSE && (config.protocol == AetherProtocol.WG || config.protocol == AetherProtocol.GOOL)) {
            abortTorChain("Tor reverse requires MASQUE")
        }
        runNativeBounded<Unit>(30000L, "Tor.start") {
            TorController.cleanStaleLocks(appContext)
            TorController.start(appContext, config)
        }
        if (!TorController.isPrepared()) abortTorChain("Tor failed to start")
        if (status.value == ConnectionStatus.RUNNING) notifyStatusChanged(appContext, ConnectionStatus.VALIDATING)
        if (!startAetherInternal(config, bindAddress, attemptId)) abortTorChain("Core failed with Tor chain")
        if (activeAttemptId.get() != attemptId) abortTorChain("Stale attempt")
        TorController.notifyCoreReady()
        if (!TorController.isRunning()) abortTorChain("Tor core not alive")
        val torPort = TorController.activePort(config)
        if (!awaitTorStable(torPort, attemptId)) abortTorChain("Tor not connected/stable")
        if (activeAttemptId.get() != attemptId) abortTorChain("Stale attempt")
        ActiveProxyProvider.torProxyUrl = TorController.getUpstreamProxy()
        if (config.torMode == TorMode.TOR) {
            try {
                val intent = Intent().setClassName(appContext.packageName, "io.github.immaghzbad.aetherst.service.AetherVpnService").apply {
                    action = "io.github.immaghzbad.aetherst.SWITCH_HEV"
                    putExtra("host", "127.0.0.1")
                    putExtra("port", torPort)
                }
                appContext.startService(intent)
            } catch (_: Exception) {}
            delay(500.milliseconds)
        }
        torChaining = false
        notifyStatusChanged(appContext, ConnectionStatus.RUNNING)
        startTimer()
    }

    private suspend fun startTorOnly(config: AetherConfig, bindAddress: String, attemptId: Long) {
        runNativeBounded<Unit>(30000L, "Tor.start") {
            TorController.cleanStaleLocks(appContext)
            TorController.start(appContext, config)
        }
        if (!TorController.isPrepared()) abortTorChain("Tor failed to start")
        runner.start(config, bindAddress, onCodeRequired = { updateIsWaitingForCode(true) }, inputProvider = { loginCodeChannel.receive() })
        val bindPort = config.socksPort.toIntOrNull() ?: 1819
        val deadline = System.currentTimeMillis() + 180_000
        var socksReady = false
        while (currentCoroutineContext().isActive) {
            if (activeAttemptId.get() != attemptId) return
            val coreStatus = runner.connectionStatus.value
            if (coreStatus == ConnectionStatus.SOCKS_READY || probeSocksReady("127.0.0.1", bindPort)) {
                socksReady = true
                break
            }
            if (coreStatus == ConnectionStatus.ERROR || coreStatus == ConnectionStatus.STOPPED || System.currentTimeMillis() > deadline) {
                abortTorChain("Tor-only proxy failed")
            }
            delay(1000.milliseconds)
        }
        if (!socksReady) abortTorChain("Tor-only proxy not ready")
        TorController.notifyCoreReady()
        TorController.notifyProxyReady(bindPort)
        ActiveProxyProvider.torProxyUrl = TorController.getUpstreamProxy()
        notifyStatusChanged(appContext, ConnectionStatus.RUNNING)
        startTimer()
        torChaining = false
    }

    private suspend fun abortTorChain(reason: String): Nothing {
        ActiveProxyProvider.torProxyUrl = null
        TorController.notifyCoreDead()
        TorController.stop()
        torChaining = false
        runCatching { runner.stop() }
        throw IllegalStateException(reason)
    }

    private suspend fun awaitTorStable(port: Int, attemptId: Long, timeoutSec: Int = 120, stableMs: Long = 3000): Boolean {
        var wait = 0
        var ready = false
        while (wait < timeoutSec && !ready) {
            if (activeAttemptId.get() != attemptId) return false
            if (TorController.isRunning() && probeSocksReady("127.0.0.1", port) && verifyPortListening("127.0.0.1", port)) {
                TorController.notifyProxyReady(port)
                ready = true
            } else {
                delay(1000.milliseconds)
                wait++
            }
        }
        if (!ready) return false
        var stable = 0
        while (stable < 15 && !TorController.stableFor(stableMs)) {
            if (activeAttemptId.get() != attemptId) return false
            delay(500.milliseconds)
            stable++
        }
        return TorController.isConnected()
    }

    private suspend fun startAetherInternal(config: AetherConfig, bindAddress: String, attemptId: Long): Boolean {
        val effective = config
        mimGuardActive = true
        Bridge.mimGuardActive = true
        try {
            if (activeAttemptId.get() != attemptId) return false
            if (!startAetherValidated(effective, bindAddress, attemptId)) {
                return false
            }
            if (activeAttemptId.get() != attemptId) return false
            return finishCoreStart(effective, attemptId)
        } finally {
            mimGuardActive = false
            Bridge.mimGuardActive = false
        }
    }

    private suspend fun finishCoreStart(config: AetherConfig, attemptId: Long): Boolean {
        if (torChaining) return true
        publishCoreActive(config.socksPort.toIntOrNull() ?: 1819, attemptId)
        return true
    }

    private suspend fun publishCoreActive(proxyPort: Int, attemptId: Long) {
        if (activeAttemptId.get() != attemptId) return
        if (!verifyPortListening("127.0.0.1", proxyPort)) throw IllegalStateException("Proxy port $proxyPort is not listening")
        delay(300.milliseconds)
        if (activeAttemptId.get() != attemptId) return
        notifyStatusChanged(appContext, ConnectionStatus.RUNNING)
        startTimer()
        LogRepository.i("[Controller] Core is active and validated on port $proxyPort")
    }

    private fun lastconnFiles(): List<java.io.File> {
        return try {
            appContext.filesDir.listFiles()?.filter { it.name.startsWith("aether-") && it.name.contains("lastconn") && it.name.endsWith(".toml") } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearLastconn() {
        for (f in lastconnFiles()) {
            try { f.delete() } catch (_: Exception) {}
        }
    }

    private suspend fun startAetherValidated(config: AetherConfig, bindAddress: String, attemptId: Long): Boolean {
        val chainingTor = torChaining
        LogRepository.i("[Controller] Starting core at $bindAddress")
        runner.start(config, bindAddress, onCodeRequired = { updateIsWaitingForCode(true) }, inputProvider = { loginCodeChannel.receive() })
        val proxyPort = config.socksPort.toIntOrNull() ?: 1819
        var dpValidated = false
        var lastStatus = runner.connectionStatus.value
        var lastProgressAt = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            if (activeAttemptId.get() != attemptId) return false
            val coreStatus = runner.connectionStatus.value
            if (coreStatus != lastStatus) {
                lastStatus = coreStatus
                lastProgressAt = System.currentTimeMillis()
                LogRepository.i("[Controller] Core status -> $coreStatus")
            }
            if (coreStatus == ConnectionStatus.DATAPLANE_VALIDATED || coreStatus == ConnectionStatus.SOCKS_READY) {
                dpValidated = true
                break
            }
            if (coreStatus == ConnectionStatus.ERROR) throw IllegalStateException("Core reported error during startup")
            if (coreStatus == ConnectionStatus.STOPPED) throw IllegalStateException("Core stopped unexpectedly during startup")
            if (isWaitingForCode.value) {
                lastProgressAt = System.currentTimeMillis()
                delay(1000.milliseconds)
                continue
            }
            if (System.currentTimeMillis() - lastProgressAt > 90_000) {
                throw IllegalStateException("Core timed out waiting for data-plane validation")
            }
            delay(200.milliseconds)
        }
        if (!dpValidated) throw IllegalStateException("Core data-plane validation failed")
        if (!chainingTor) {
            notifyStatusChanged(appContext, ConnectionStatus.DATAPLANE_VALIDATED)
        }
        val socksReady = withTimeoutOrNull(30.seconds) {
            while (currentCoroutineContext().isActive) {
                if (activeAttemptId.get() != attemptId) return@withTimeoutOrNull false
                val coreStatus = runner.connectionStatus.value
                if (coreStatus == ConnectionStatus.SOCKS_READY || isPortListening("127.0.0.1", proxyPort)) return@withTimeoutOrNull true
                if (coreStatus == ConnectionStatus.ERROR || coreStatus == ConnectionStatus.STOPPED) throw IllegalStateException("Core stopped during SOCKS check")
                delay(200.milliseconds)
            }
            false
        } ?: false
        if (!socksReady) throw IllegalStateException("SOCKS proxy not ready")
        if (!chainingTor) {
            notifyStatusChanged(appContext, ConnectionStatus.SOCKS_READY)
        }
        return true
    }

    override suspend fun stop() {
        mutex.withLock {
            if (_status.value == ConnectionStatus.STOPPED) {
                stopTimer()
                ActiveProxyProvider.psiphonProxyUrl = null
                ActiveProxyProvider.torProxyUrl = null
                return@withLock
            }

            psiphonChaining = false
            torChaining = false
            val attemptId = activeAttemptId.get()
            notifyStatusChanged(appContext, ConnectionStatus.STOPPING)

            try {
                withTimeoutOrNull(8000.milliseconds) {
                    withContext(Dispatchers.IO) {
                        runNativeBounded(2500L, "Cloak.stop") { CloakController.stop() }
                        runNativeBounded(2500L, "Psiphon.stop") { PsiphonController.stop() }
                        runNativeBounded(2500L, "Tor.stop") { TorController.stop() }
                    }
                    ActiveProxyProvider.psiphonProxyUrl = null
                    ActiveProxyProvider.torProxyUrl = null
                    runCatching { cleanup(attemptId) }
                }
            } catch (_: Exception) {
            } finally {
                ActiveProxyProvider.psiphonProxyUrl = null
                ActiveProxyProvider.torProxyUrl = null
                stopTimer()
                runCatching { withTimeoutOrNull(1500.milliseconds) { cleanup(attemptId) } }
                notifyStatusChanged(appContext, ConnectionStatus.STOPPED)
            }
        }
    }

    private suspend fun awaitPsiphonStable(timeoutSec: Int = 30, stableMs: Long = 2000): Boolean {
        var wait = 0
        while (wait < timeoutSec && !PsiphonController.isConnected()) { delay(500.milliseconds); wait++ }
        var stable = 0
        while (stable < 10 && !PsiphonController.stableFor(stableMs)) { delay(300.milliseconds); stable++ }
        return PsiphonController.isConnected()
    }

    private suspend fun <T> runNativeBounded(timeoutMs: Long, label: String, block: () -> T): T? {
        return withContext(Dispatchers.IO) {
            val done = CompletableDeferred<T?>()
            val thread = Thread {
                try { done.complete(block()) } catch (_: Throwable) { done.complete(null) }
            }
            thread.isDaemon = true
            thread.name = "native-$label"
            thread.start()
            withTimeoutOrNull(timeoutMs.milliseconds) { done.await() }
        }
    }

    private suspend fun cleanup(attemptId: Long) {
        if (activeAttemptId.get() == attemptId) activeAttemptId.set(0)
        runner.stop()
        updateIsWaitingForCode(false)
        runCatching {
            var r = loginCodeChannel.tryReceive()
            while (r.isSuccess) r = loginCodeChannel.tryReceive()
        }
        delay(200.milliseconds)
    }

    private fun handleCoreStatus(coreStatus: ConnectionStatus) {
        _status.update { current ->
            if (current == ConnectionStatus.STOPPED) return@update current
            if (psiphonChaining || torChaining) return@update current
            if (current == ConnectionStatus.STOPPING && coreStatus != ConnectionStatus.STOPPED) return@update current

            val next = when (coreStatus) {
                ConnectionStatus.ERROR -> {
                    TorController.notifyCoreDead()
                    stopTimer()
                    ConnectionStatus.ERROR
                }
                ConnectionStatus.STOPPED -> {
                    TorController.notifyCoreDead()
                    if (current == ConnectionStatus.RUNNING || current == ConnectionStatus.RECONNECTING) {
                        if (mimGuardActive) return@update current
                        stopTimer()
                        ConnectionStatus.ERROR
                    } else if (current == ConnectionStatus.STOPPING) {
                        stopTimer()
                        ConnectionStatus.STOPPED
                    } else {
                        stopTimer()
                        coreStatus
                    }
                }
                ConnectionStatus.RECONNECTING -> {
                    if (current != ConnectionStatus.RECONNECTING) {
                        pauseTimer()
                        ConnectionStatus.RECONNECTING
                    } else current
                }
                ConnectionStatus.SOCKS_READY -> {
                    if (current == ConnectionStatus.RECONNECTING) {
                        resumeTimer()
                        ConnectionStatus.RUNNING
                    } else current
                }
                ConnectionStatus.RUNNING -> {
                    if (current != ConnectionStatus.RUNNING) {
                        if (current == ConnectionStatus.RECONNECTING) resumeTimer() else startTimer()
                        ConnectionStatus.RUNNING
                    } else current
                }
                else -> current
            }

            if (next != current) notifyStatusChanged(appContext, next)
            next
        }
    }

    private suspend fun isPortListening(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 800)
                true
            }
        }.getOrDefault(false)
    }

    private suspend fun verifyPortListening(host: String, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening(host, port)) return true
            delay(300.milliseconds)
        }
        return false
    }

    private suspend fun probeSocksReady(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 2500)
                socket.soTimeout = 4000
                val ins = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(byteArrayOf(5, 1, 0))
                out.flush()
                val method = ByteArray(2)
                if (!fillStream(ins, method)) return@runCatching false
                if (method[0] != 5.toByte() || method[1] != 0.toByte()) return@runCatching false
                val addr = InetAddress.getByName("1.1.1.1").address
                val req = ByteArray(11)
                req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 1
                System.arraycopy(addr, 0, req, 4, 4)
                req[8] = 0; req[9] = 80.toByte()
                out.write(req)
                out.flush()
                val hdr = ByteArray(4)
                if (!fillStream(ins, hdr)) return@runCatching false
                hdr[1].toInt() and 0xFF == 0
            }
        }.getOrDefault(false)
    }

    private fun fillStream(ins: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = ins.read(buffer, offset, buffer.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun startTimer() {
        timerJob?.cancel()
        durationSeconds = 0L
        prevTotalTx = 0L
        prevTotalRx = 0L
        timerJob = scope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                durationSeconds++
                Bridge.elapsedOverride.value = durationSeconds
                if (useTrafficStatsFallback && !hasManualTraffic) updateTrafficFromStats()
            }
        }
    }

    private fun pauseTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun resumeTimer() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                durationSeconds++
                Bridge.elapsedOverride.value = durationSeconds
                if (useTrafficStatsFallback && !hasManualTraffic) updateTrafficFromStats()
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        durationSeconds = 0L
        Bridge.elapsedOverride.value = 0L
        Bridge.trafficOverride.value = SessionTraffic()
        hasManualTraffic = false
        accumulatedTx = 0L
        accumulatedRx = 0L
        lastEngineTx = 0L
        lastEngineRx = 0L
        prevTotalTx = 0L
        prevTotalRx = 0L
        baseTx = 0L
        baseRx = 0L
    }

    private fun updateTrafficFromStats() {
        val rawTx = TrafficStats.getUidTxBytes(Process.myUid())
        val rawRx = TrafficStats.getUidRxBytes(Process.myUid())
        if (rawTx == TrafficStats.UNSUPPORTED.toLong() || rawRx == TrafficStats.UNSUPPORTED.toLong()) return
        val currentTx = if (rawTx < 0) 0L else rawTx
        val currentRx = if (rawRx < 0) 0L else rawRx
        val diffTx = (currentTx - baseTx).coerceAtLeast(0)
        val diffRx = (currentRx - baseRx).coerceAtLeast(0)
        val uploadSpeed = (diffTx - prevTotalTx).coerceAtLeast(0).toDouble()
        val downloadSpeed = (diffRx - prevTotalRx).coerceAtLeast(0).toDouble()
        prevTotalTx = diffTx
        prevTotalRx = diffRx
        Bridge.trafficOverride.value = SessionTraffic(diffTx, diffRx, uploadSpeed, downloadSpeed)
    }

    fun setTraffic(tx: Long, rx: Long) {
        val safeTx = tx.coerceAtLeast(0)
        val safeRx = rx.coerceAtLeast(0)
        if (!hasManualTraffic) {
            hasManualTraffic = true
            if (safeTx == 0L && safeRx == 0L) {
                lastEngineTx = 0L; lastEngineRx = 0L; accumulatedTx = 0L; accumulatedRx = 0L; prevTotalTx = 0L; prevTotalRx = 0L
                Bridge.trafficOverride.value = SessionTraffic(0, 0, 0.0, 0.0)
                return
            }
        }
        if (safeTx < lastEngineTx || safeRx < lastEngineRx) {
            accumulatedTx += lastEngineTx
            accumulatedRx += lastEngineRx
        }
        lastEngineTx = safeTx
        lastEngineRx = safeRx
        val totalTx = accumulatedTx + safeTx
        val totalRx = accumulatedRx + safeRx
        val uploadSpeed = (totalTx - prevTotalTx).coerceAtLeast(0).toDouble()
        val downloadSpeed = (totalRx - prevTotalRx).coerceAtLeast(0).toDouble()
        prevTotalTx = totalTx
        prevTotalRx = totalRx
        Bridge.trafficOverride.value = SessionTraffic(totalTx, totalRx, uploadSpeed, downloadSpeed)
    }
}
