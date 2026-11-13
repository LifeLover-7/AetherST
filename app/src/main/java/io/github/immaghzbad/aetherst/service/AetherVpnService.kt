package io.github.immaghzbad.aetherst.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.immaghzbad.aetherst.MainActivity
import io.github.immaghzbad.aetherst.R
import io.github.immaghzbad.aetherst.core.ConnectionController
import io.github.immaghzbad.aetherst.core.DnsMap
import io.github.immaghzbad.aetherst.core.HevEngineSettings
import io.github.immaghzbad.aetherst.core.HevTun2SocksConfig
import io.github.immaghzbad.aetherst.core.HevTun2SocksEngine
import io.github.immaghzbad.aetherst.core.HevTun2SocksNative
import io.github.immaghzbad.aetherst.core.PsiphonController
import io.github.immaghzbad.aetherst.core.TorController
import io.github.immaghzbad.aetherst.core.RoutingEngine
import io.github.immaghzbad.aetherst.core.SocksTunBridge
import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.shared.data.ActiveProxyProvider
import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.model.AetherIpMode
import io.github.immaghzbad.aetherst.shared.model.ChainProvider
import io.github.immaghzbad.aetherst.shared.model.ConnectionMode
import io.github.immaghzbad.aetherst.shared.model.ConnectionStatus
import io.github.immaghzbad.aetherst.shared.model.CustomWireGuardConfig
import io.github.immaghzbad.aetherst.shared.model.ExitNodeMode
import io.github.immaghzbad.aetherst.shared.model.RoutingMode
import io.github.immaghzbad.aetherst.shared.model.TunnelEngine
import io.github.immaghzbad.aetherst.shared.net.CustomSocks5Relay
import io.github.immaghzbad.aetherst.shared.net.WireGuardExitTunnel
import io.github.immaghzbad.aetherst.shared.platform.Bridge
import io.github.immaghzbad.aetherst.shared.util.WireGuardConfigParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

@Suppress("VpnServicePolicy")
class AetherVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var hevEngine: HevTun2SocksEngine? = null
    private var socksBridge: SocksTunBridge? = null
    private var routingEngine: RoutingEngine? = null
    private var activeTunnelEngine: TunnelEngine? = null
    private var customSocksRelay: CustomSocks5Relay? = null
    private var wgExitTunnel: WireGuardExitTunnel? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val activeAttemptId = AtomicLong(0)
    private val commandCounter = AtomicLong(0)
    private var startupJob: Job? = null
    private var statsJob: Job? = null
    private var lastHevUpstream: String? = null
    private var lastBridgeUpstream: String? = null

    private var isUserInitiatedStop = false
    private var wasEverRunning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkCallbackRegistered = false
    private var autoReconnectJob: Job? = null
    private var lastNetworkLostTime: Long = 0L

    companion object {
        const val ACTION_START = "io.github.immaghzbad.aetherst.ACTION_START"
        const val ACTION_STOP = "io.github.immaghzbad.aetherst.ACTION_STOP"
        const val ACTION_RESTART = "io.github.immaghzbad.aetherst.ACTION_RESTART"
        const val ACTION_SWITCH_HEV = "io.github.immaghzbad.aetherst.SWITCH_HEV"
        const val CHANNEL_ID = "aether_vpn_status_v2"
        const val ALERT_CHANNEL_ID = "aether_vpn_alerts"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1003

        fun startVpn(context: Context): Boolean = runCatching {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
            true
        }.getOrElse {
            LogRepository.e("[VpnService] Start failed: ${it.localizedMessage}")
            false
        }

        fun stopVpn(context: Context): Boolean = runCatching {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
            true
        }.getOrElse {
            LogRepository.e("[VpnService] Stop failed: ${it.localizedMessage}")
            false
        }

        fun restartVpn(context: Context): Boolean = runCatching {
            val intent = Intent(context, AetherVpnService::class.java).apply { action = ACTION_RESTART }
            context.startForegroundService(intent)
            true
        }.getOrElse {
            LogRepository.e("[VpnService] Restart failed: ${it.localizedMessage}")
            false
        }
    }

    private fun getController() = ConnectionController.getInstance(this)

    override fun onCreate() {
        super.onCreate()
        LogRepository.initialize(getSettings(PlatformContext(this)))
        PsiphonController.setVpnService(this)
        TorController.setVpnService(this)
        createNotificationChannel()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AetherST:VpnWakeLock")

        scope.launch {
            ConnectionController.status.collect { status ->
                updateNotification()
                handleTunLifecycle(status)
                handleAutoReconnectOnStatus(status)
                runCatching { AetherWidgetProvider.updateAllWidgets(this@AetherVpnService) }
            }
        }

        scope.launch {
            AetherConfigRepository.getInstance(getSettings(PlatformContext(this@AetherVpnService))).config.collect {
                routingEngine?.clearCache()
            }
        }
        registerNetworkMonitor()
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    scope.launch { handleNetworkLost() }
                }
                override fun onAvailable(network: Network) {
                    scope.launch { handleNetworkAvailable() }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        scope.launch { handleNetworkAvailable() }
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            LogRepository.w("[VpnService] Network monitor registration failed: ${e.message}")
        }
    }

    private fun unregisterNetworkMonitor() {
        if (!isNetworkCallbackRegistered) {
            networkCallback = null
            return
        }
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            LogRepository.w("[VpnService] unregisterNetworkCallback failed: ${e.message}")
        }
        networkCallback = null
        isNetworkCallbackRegistered = false
    }

    private fun handleNetworkLost() {
        val status = ConnectionController.status.value
        val config = AetherConfigRepository.getInstance(getSettings(PlatformContext(this))).config.value
        if (!config.smartReconnect || isUserInitiatedStop) return
        if (status == ConnectionStatus.RUNNING || status == ConnectionStatus.TUN_ACTIVE) {
            lastNetworkLostTime = System.currentTimeMillis()
            LogRepository.w("[VpnService] Network lost while $status -> RECONNECTING")
            try {
                getController().markReconnecting()
            } catch (e: Exception) {
                Bridge.statusOverride.value = ConnectionStatus.RECONNECTING
            }
        }
    }

    private fun handleNetworkAvailable() {
        val status = ConnectionController.status.value
        val config = AetherConfigRepository.getInstance(getSettings(PlatformContext(this))).config.value
        if (!config.smartReconnect || isUserInitiatedStop) return
        if (status == ConnectionStatus.RECONNECTING || status == ConnectionStatus.ERROR || status == ConnectionStatus.FAILED) {
            autoReconnectJob?.cancel()
            autoReconnectJob = scope.launch {
                delay(1500.milliseconds)
                if (!isNetworkValidated()) delay(2000.milliseconds)
                if (!isNetworkValidated()) return@launch
                val cur = ConnectionController.status.value
                if (cur == ConnectionStatus.RECONNECTING || cur == ConnectionStatus.ERROR || cur == ConnectionStatus.FAILED) {
                    LogRepository.i("[VpnService] Network restored -> auto-reconnect from $cur")
                    withContext(Dispatchers.Main) {
                        try {
                            if (vpnInterface == null) {
                                startAttempt(commandCounter.incrementAndGet())
                            } else {
                                hevEngine?.resume()
                            }
                        } catch (_: Exception) {
                            startAttempt(commandCounter.incrementAndGet())
                        }
                    }
                    delay(3000.milliseconds)
                    if (ConnectionController.status.value == ConnectionStatus.RECONNECTING) {
                        getController().start()
                    }
                }
            }
        }
    }

    private fun isNetworkValidated(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    private fun handleAutoReconnectOnStatus(status: ConnectionStatus) {
        if (isUserInitiatedStop || ConnectionController.mimGuardActive) return
        val cfg = AetherConfigRepository.getInstance(getSettings(PlatformContext(this))).config.value
        if (!cfg.smartReconnect) return
        if (status == ConnectionStatus.ERROR || status == ConnectionStatus.FAILED) {
            autoReconnectJob?.cancel()
            autoReconnectJob = scope.launch {
                var attempts = 0
                while (attempts < cfg.reconnectRetryLimit && isActive) {
                    if (!isNetworkValidated()) {
                        delay(3000.milliseconds)
                        continue
                    }
                    delay((cfg.reconnectSecs * 1000L).coerceAtLeast(2000L).milliseconds)
                    val cur = ConnectionController.status.value
                    if (cur != ConnectionStatus.ERROR && cur != ConnectionStatus.FAILED) break
                    if (isUserInitiatedStop) break
                    attempts++
                    LogRepository.i("[VpnService] Auto-reconnect attempt $attempts/${cfg.reconnectRetryLimit} from $cur")
                    try {
                        getController().start()
                    } catch (e: Exception) {
                        LogRepository.e("[VpnService] Auto-reconnect start failed: ${e.message}")
                    }
                    delay(5000.milliseconds)
                }
            }
        } else if (status == ConnectionStatus.RUNNING || status == ConnectionStatus.STOPPED) {
            autoReconnectJob?.cancel()
            autoReconnectJob = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isUserInitiatedStop = false
                autoReconnectJob?.cancel()
                autoReconnectJob = null
                showInitialNotification()
                startAttempt(commandCounter.incrementAndGet())
            }
            ACTION_RESTART -> {
                isUserInitiatedStop = false
                showInitialNotification()
                restartTunnel(commandCounter.incrementAndGet())
            }
            ACTION_STOP -> {
                isUserInitiatedStop = true
                showInitialNotification()
                stopVpnService(commandCounter.incrementAndGet())
            }
            ACTION_SWITCH_HEV -> {
                showInitialNotification()
                val host = intent.getStringExtra("host") ?: "127.0.0.1"
                val port = intent.getIntExtra("port", 3080)
                scope.launch { switchHevInternal(host, port) }
            }
            else -> {
                if (isUserInitiatedStop) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        isUserInitiatedStop = false
        LogRepository.w("[VpnService] VPN revoked by system or other app")
        val config = AetherConfigRepository.getInstance(getSettings(PlatformContext(this))).config.value
        val attemptId = activeAttemptId.getAndSet(0)
        runCatching { startupJob?.cancel() }
        stopStatsJob()
        runCatching {
            kotlinx.coroutines.runBlocking {
                stateMutex.withLock {
                    hevEngine?.requestStop()
                    hevEngine = null
                    socksBridge?.stop()
                    socksBridge = null
                    closeVpnInterface(attemptId)
                    activeTunnelEngine = null
                }
            }
        }
        scope.launch {
            if (config.connectionMode != ConnectionMode.PROXY_ONLY) {
                getController().stop()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startAttempt(commandId: Long) {
        startupJob?.cancel()
        startupJob = scope.launch {
            if (commandCounter.get() != commandId) return@launch

            val attemptId = stateMutex.withLock {
                if (commandCounter.get() != commandId) return@launch
                val current = ConnectionController.status.value
                if (current == ConnectionStatus.RUNNING || current == ConnectionStatus.VALIDATING) return@launch

                val id = System.currentTimeMillis()
                activeAttemptId.set(id)
                id
            }

            runCatching { wakeLock?.acquire(4 * 60 * 60 * 1000L) }

            try {
                val config = AetherConfigRepository.getInstance(getSettings(PlatformContext(this@AetherVpnService))).config.value
                routingEngine = RoutingEngine(config.routingRules)

                val hasDomainRules = config.routingRules.any { it.mode == RoutingMode.BLOCK || it.mode == RoutingMode.DIRECT }
                val effectiveEngine = if (
                    !config.tunnelAllApps && config.tunnelEngine == TunnelEngine.HEV_TUN2SOCKS && hasDomainRules
                ) {
                    TunnelEngine.SOCKS_TUN_BRIDGE
                } else {
                    config.tunnelEngine
                }
                activeTunnelEngine = effectiveEngine

                // گام ۱: ابتدا هسته و زنجیره (Psiphon/Tor) بدون اختلال تونل مجازی متصل و تایید شوند
                getController().start()
                if (ConnectionController.status.value != ConnectionStatus.RUNNING) {
                    throw IllegalStateException("Core failed to start")
                }
                ensureCurrentAttempt(attemptId)

                // گام ۱.۵: راه‌اندازی استخر سایفون در صورت فعال بودن ExitNodeMode.PSIPHON_POOL
                if (config.exitNodeMode == ExitNodeMode.PSIPHON_POOL && !PsiphonController.isRunning()) {
                    val httpUpstream = "http://127.0.0.1:${config.httpPort.ifBlank { "1820" }}"
                    LogRepository.i("[VpnService] Starting Psiphon multi-core pool over primary core (upstream=$httpUpstream)")
                    PsiphonController.start(this@AetherVpnService, config, upstream = httpUpstream)
                    var wait = 0
                    while (wait < 30 && !PsiphonController.isConnected()) {
                        delay(1000.milliseconds)
                        wait++
                    }
                    if (PsiphonController.isRunning()) {
                        ActiveProxyProvider.psiphonProxyUrl = PsiphonController.getUpstreamProxy()
                        LogRepository.i("[VpnService] Psiphon multi-instance pool ready at ${ActiveProxyProvider.psiphonProxyUrl}")
                    } else {
                        LogRepository.w("[VpnService] Psiphon pool wait timed out, falling back to direct")
                    }
                }

                // گام ۲: پس از آماده شدن کامل پورت، کارت شبکه VPN و موتور تانل مستقیماً روی پورت نهایی بالا بیایند
                if (vpnInterface == null) {
                    if (!establishVpnTun(attemptId, effectiveEngine)) throw IllegalStateException("TUN establishment failed")
                }
                ensureCurrentAttempt(attemptId)
                val descriptor = vpnInterface ?: throw IllegalStateException("TUN descriptor unavailable")

                val effectiveMtu = 1280.coerceAtMost(config.mtu.coerceIn(576, 9000))
                if (effectiveEngine == TunnelEngine.HEV_TUN2SOCKS) {
                    if (!HevTun2SocksNative.isAvailable) throw IllegalStateException("HEV Native library not available")

                    hevEngine?.requestStop()
                    hevEngine = HevTun2SocksEngine()

                    val hevSettings = HevEngineSettings(
                        logLevel = config.hevLogLevel,
                        connectTimeoutMs = config.hevConnectTimeoutMs,
                        readWriteTimeoutMs = config.hevReadWriteTimeoutMs,
                        maxSessionCount = config.hevMaxSessionCount,
                        mapdnsCacheSize = config.hevMapdnsCacheSize
                    )

                    val (hevHost, hevPort) = resolveEffectiveSocks(config, ActiveProxyProvider.psiphonProxyUrl)
                    val ok = hevEngine?.start(
                        tunPfd = descriptor,
                        socksAddress = hevHost,
                        socksPort = hevPort,
                        mtu = effectiveMtu,
                        attemptId = attemptId,
                        settings = hevSettings,
                        udpMode = if (ActiveProxyProvider.psiphonProxyUrl != null || config.chainProvider == ChainProvider.PSIPHON || config.exitNodeMode == ExitNodeMode.PSIPHON_POOL || config.chainProvider == ChainProvider.TOR) "tcp" else config.hevUdpMode
                    ) == true
                    if (!ok) throw IllegalStateException("HEV engine failed to start mtu=$effectiveMtu")
                    lastHevUpstream = "$hevHost:$hevPort"
                } else {
                    socksBridge?.stop()
                    val psiphonUrl = ActiveProxyProvider.psiphonProxyUrl
                    val (bridgeHost, bridgePort) = resolveEffectiveSocks(config, psiphonUrl)
                    socksBridge = SocksTunBridge(
                        vpnService = this@AetherVpnService,
                        tunDescriptor = descriptor,
                        socksHost = bridgeHost,
                        socksPort = bridgePort,
                        mtu = effectiveMtu,
                        blockedPackagesProvider = {
                            AetherConfigRepository.getInstance(getSettings(PlatformContext(this@AetherVpnService))).config.value.blockedPackages
                        },
                        routingEngine = routingEngine!!
                    ).apply { start() }
                    lastBridgeUpstream = "$bridgeHost:$bridgePort"
                }

                wasEverRunning = true
                startStatsJob()
                LogRepository.i("[VpnService] VPN tunnel active")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (activeAttemptId.get() == attemptId && commandCounter.get() == commandId) {
                    rollback(attemptId, throwable.localizedMessage ?: "Startup failed")
                }
            }
        }
    }

    private fun ensureCurrentAttempt(attemptId: Long) {
        if (activeAttemptId.get() != attemptId) throw IllegalStateException("Connection attempt invalidated")
    }

    private fun establishVpnTun(attemptId: Long, engine: TunnelEngine): Boolean = runCatching {
        val config = AetherConfigRepository.getInstance(getSettings(PlatformContext(this))).config.value
        val effectiveMtu = 1280.coerceAtMost(config.mtu.coerceIn(576, 9000))
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val builder = Builder()
            .setMtu(effectiveMtu)
            .setSession("AetherST Tunnel")
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags))

        val wgConfig = if (config.exitNodeMode == ExitNodeMode.CUSTOM_WG) config.customWgConfig else null
        val supportsIpv6 = config.effectiveIpMode() == AetherIpMode.DUAL || config.effectiveIpMode() == AetherIpMode.IPV6 || config.ipv6Leak
        val filteredWg = wgConfig?.filterForPrimarySupport(supportsIpv6)

        builder.addAddress("198.18.0.1", 24)
        builder.addAddress("fd00::1", 120)

        if (filteredWg != null && filteredWg.addressV4.isNotBlank()) {
            val v4Prefix = filteredWg.addressV4.substringBefore("/")
            val v4Cidr = filteredWg.addressV4.substringAfter("/", "24").toIntOrNull() ?: 24
            if (v4Prefix != "198.18.0.1") {
                runCatching { builder.addAddress(v4Prefix, v4Cidr) }
            }
            if (filteredWg.addressV6.isNotBlank()) {
                val v6Prefix = filteredWg.addressV6.substringBefore("/")
                val v6Cidr = filteredWg.addressV6.substringAfter("/", "120").toIntOrNull() ?: 120
                if (v6Prefix != "fd00::1") {
                    runCatching { builder.addAddress(v6Prefix, v6Cidr) }
                }
            }
        }
        builder.addRoute("0.0.0.0", 0)

        if (filteredWg != null && filteredWg.dnsServers.isNotEmpty()) {
            for (dns in filteredWg.dnsServers) {
                runCatching { builder.addDnsServer(dns) }
            }
        } else if (engine == TunnelEngine.HEV_TUN2SOCKS) {
            builder.addDnsServer(HevTun2SocksConfig.MAP_DNS_ADDRESS)
        } else {
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("2606:4700:4700::1111")
            builder.addDnsServer("2001:4860:4860::8888")
        }

        if ((filteredWg?.allowedIpsV6?.isNotEmpty() == true || config.ipv6Leak) && Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            runCatching { builder.addRoute("::", 0) }
        }

        if (config.tunnelAllApps) {
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            try { builder.addDisallowedApplication(applicationContext.packageName) } catch (_: Exception) {}

            // اصلاح اصلی: در حالت تونل کل دستگاه، برنامه‌های Bypass در excludedPackages قرار دارند!
            val bypassPackages = config.excludedPackages

            var bypassedCount = 0
            for (pkg in bypassPackages) {
                val cleanPkg = pkg.trim()
                if (cleanPkg.isEmpty() || cleanPkg == packageName || cleanPkg == applicationContext.packageName) continue
                if (config.blockedPackages.contains(cleanPkg)) continue
                try {
                    builder.addDisallowedApplication(cleanPkg)
                    bypassedCount++
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.w("[Tun] Ignoring uninstalled package: $cleanPkg")
                } catch (e: Exception) {
                    LogRepository.w("[Tun] Skipping disallow $cleanPkg: ${e.message}")
                }
            }
            LogRepository.i("[Tun] Whole-device: tunneling all apps EXCEPT $bypassedCount bypassed packages")
        } else {
            if (config.tunneledPackages.isNotEmpty() || config.blockedPackages.isNotEmpty()) {
                var added = 0
                (config.tunneledPackages + config.blockedPackages)
                    .asSequence()
                    .filterNot { it == packageName || it == applicationContext.packageName }
                    .forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg.trim())
                            added++
                        } catch (_: PackageManager.NameNotFoundException) {
                            LogRepository.w("[Tun] Ignoring uninstalled package: $pkg")
                        } catch (e: Exception) {
                            LogRepository.w("[Tun] Skipping package $pkg: ${e.message}")
                        }
                    }
                LogRepository.i("[Tun] Per-app mode: $added apps routed through VPN, rest bypassed")
            } else {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                try {
                    val pm = packageManager
                    val allPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                    } else {
                        @Suppress("DEPRECATION") pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    }
                    var disallowed = 0
                    for (app in allPkgs) {
                        val pkg = app.packageName
                        if (pkg == packageName || config.blockedPackages.contains(pkg)) continue
                        try {
                            builder.addDisallowedApplication(pkg)
                            disallowed++
                        } catch (_: Exception) {}
                    }
                    LogRepository.i("[Tun] Per-app mode: 0 tunneled, $disallowed apps bypassed")
                } catch (e: Exception) {
                    LogRepository.w("[Tun] Failed to enumerate apps for all-bypass: ${e.message}")
                }
            }
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            val prep = VpnService.prepare(this@AetherVpnService)
            LogRepository.e("[Tun] [attempt=$attemptId] Failed: builder.establish() returned null prepare=${prep != null}")
            return@runCatching false
        }
        LogRepository.i("[Tun] [attempt=$attemptId] Established mtu=$effectiveMtu engine=$engine")
        true
    }.getOrElse {
        LogRepository.e("[Tun] [attempt=$attemptId] Failed: ${it.localizedMessage}")
        false
    }

    private suspend fun rollback(attemptId: Long, reason: String) {
        LogRepository.e("[VpnService] Rollback: $reason")
        stopStatsJob()
        val status = ConnectionController.status.value
        val pauseOnly = !isUserInitiatedStop &&
                (status == ConnectionStatus.RECONNECTING || status == ConnectionStatus.DATAPLANE_VALIDATED || status == ConnectionStatus.SOCKS_READY)
        if (wasEverRunning && !isUserInitiatedStop) {
            showDisconnectionAlert(reason)
        }
        cleanupResources(attemptId)
        if (!pauseOnly) {
            getController().stop()
        }
    }

    private fun stopVpnService(commandId: Long) {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        wasEverRunning = false
        scope.launch {
            val attemptId = activeAttemptId.getAndSet(0)
            startupJob?.cancelAndJoin()
            stopStatsJob()

            cleanupResources(attemptId, forceTeardown = true)
            if (commandCounter.get() != commandId) return@launch

            runCatching { getController().stop() }.onFailure {
                LogRepository.e("[VpnService] Controller stop failed: ${it.localizedMessage}")
            }

            if (commandCounter.get() != commandId) return@launch

            scope.launch(Dispatchers.Main) {
                if (isActive && commandCounter.get() == commandId) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun restartTunnel(commandId: Long) {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        wasEverRunning = false
        scope.launch {
            val attemptId = activeAttemptId.getAndSet(0)
            startupJob?.cancelAndJoin()
            stopStatsJob()

            cleanupResources(attemptId, forceTeardown = true)
            runCatching { getController().stop() }.onFailure {
                LogRepository.e("[VpnService] Controller stop failed during restart: ${it.localizedMessage}")
            }

            if (commandCounter.get() != commandId || isUserInitiatedStop) return@launch
            startAttempt(commandCounter.incrementAndGet())
        }
    }

    private suspend fun cleanupResources(attemptId: Long, forceTeardown: Boolean = false) {
        val status = ConnectionController.status.value
        val pauseOnly = !forceTeardown && !isUserInitiatedStop &&
                (status == ConnectionStatus.RECONNECTING || status == ConnectionStatus.DATAPLANE_VALIDATED || status == ConnectionStatus.SOCKS_READY)
        stateMutex.withLock {
            if (pauseOnly) {
                stopStatsJob()
                hevEngine?.pause()
            } else {
                hevEngine?.requestStop()
                hevEngine = null
                socksBridge?.stop()
                socksBridge = null
                wgExitTunnel?.stop()
                wgExitTunnel = null
                customSocksRelay?.stop()
                customSocksRelay = null
                try { PsiphonController.stop() } catch (_: Throwable) {}
                ActiveProxyProvider.psiphonProxyUrl = null
                closeVpnInterface(attemptId)
                activeTunnelEngine = null
                lastHevUpstream = null
                lastBridgeUpstream = null
                DnsMap.clear()
            }
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
    }

    private suspend fun handleTunLifecycle(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.RECONNECTING -> {
                if (!wasEverRunning) return
                stopStatsJob()
                hevEngine?.pause()
            }
            ConnectionStatus.DATAPLANE_VALIDATED, ConnectionStatus.SOCKS_READY -> {
                if (!wasEverRunning) return
                stopStatsJob()
                hevEngine?.pause()
            }
            ConnectionStatus.RUNNING, ConnectionStatus.TUN_ACTIVE -> {
                if (!wasEverRunning) return
                val psiphonUrl = ActiveProxyProvider.psiphonProxyUrl
                val cfg = AetherConfigRepository.getInstance(getSettings(PlatformContext(this@AetherVpnService))).config.value
                val (targetHost, targetPort) = resolveEffectiveSocks(cfg, psiphonUrl)
                val target = "$targetHost:$targetPort"
                when (activeTunnelEngine) {
                    TunnelEngine.HEV_TUN2SOCKS -> {
                        if (lastHevUpstream != target && hevEngine != null && vpnInterface != null) {
                            stopStatsJob()
                            val restartDescriptor: ParcelFileDescriptor?
                            stateMutex.withLock {
                                hevEngine?.requestStop()
                                restartDescriptor = vpnInterface
                            }
                            delay(300.milliseconds)
                            if (restartDescriptor != null) {
                                val newEngine = HevTun2SocksEngine()
                                val hevSettings = HevEngineSettings(
                                    logLevel = cfg.hevLogLevel,
                                    connectTimeoutMs = cfg.hevConnectTimeoutMs,
                                    readWriteTimeoutMs = cfg.hevReadWriteTimeoutMs,
                                    maxSessionCount = cfg.hevMaxSessionCount,
                                    mapdnsCacheSize = cfg.hevMapdnsCacheSize
                                )
                                val ok = newEngine.start(
                                    tunPfd = restartDescriptor,
                                    socksAddress = targetHost,
                                    socksPort = targetPort,
                                    mtu = cfg.mtu.coerceIn(576, 9000),
                                    attemptId = activeAttemptId.get(),
                                    settings = hevSettings,
                                    udpMode = cfg.hevUdpMode
                                )
                                stateMutex.withLock {
                                    if (ok) {
                                        hevEngine = newEngine
                                        lastHevUpstream = target
                                    } else {
                                        hevEngine?.resume()
                                    }
                                }
                            }
                            if (statsJob == null) startStatsJob()
                        } else {
                            hevEngine?.resume()
                            if (statsJob == null) startStatsJob()
                            if (lastHevUpstream == null) lastHevUpstream = target
                        }
                    }
                    TunnelEngine.SOCKS_TUN_BRIDGE -> {
                        if (lastBridgeUpstream != target && socksBridge != null) {
                            socksBridge?.updateUpstream(targetHost, targetPort)
                            lastBridgeUpstream = target
                        }
                        if (statsJob == null) startStatsJob()
                    }
                    else -> {
                        if (statsJob == null) startStatsJob()
                    }
                }
                DnsMap.clear()
                routingEngine?.clearCache()
            }
            ConnectionStatus.ERROR, ConnectionStatus.FAILED -> {
                if (!wasEverRunning) return
                stopStatsJob()
                hevEngine?.pause()
            }
            else -> {}
        }
    }

    private suspend fun switchHevInternal(host: String, port: Int) {
        val target = "$host:$port"
        if (activeTunnelEngine == TunnelEngine.SOCKS_TUN_BRIDGE) {
            if (lastBridgeUpstream == target || socksBridge == null || vpnInterface == null) return
            socksBridge?.updateUpstream(host, port)
            lastBridgeUpstream = target
            DnsMap.clear()
            routingEngine?.clearCache()
            return
        }
        if (lastHevUpstream == target || hevEngine == null || vpnInterface == null) return
        stopStatsJob()
        var switchDescriptor: ParcelFileDescriptor? = null
        var switchCfg: io.github.immaghzbad.aetherst.shared.model.AetherConfig? = null
        var switchHevSettings: HevEngineSettings? = null
        stateMutex.withLock {
            hevEngine?.requestStop()
            switchDescriptor = vpnInterface
            if (switchDescriptor != null) {
                val cfg = AetherConfigRepository.getInstance(getSettings(PlatformContext(this@AetherVpnService))).config.value
                switchCfg = cfg
                switchHevSettings = HevEngineSettings(
                    logLevel = cfg.hevLogLevel,
                    connectTimeoutMs = cfg.hevConnectTimeoutMs,
                    readWriteTimeoutMs = cfg.hevReadWriteTimeoutMs,
                    maxSessionCount = cfg.hevMaxSessionCount,
                    mapdnsCacheSize = cfg.hevMapdnsCacheSize
                )
            }
        }
        val descriptor = switchDescriptor ?: return
        val cfg = switchCfg ?: return
        val hevSettings = switchHevSettings ?: return
        delay(300.milliseconds)
        val newEngine = HevTun2SocksEngine()
        val effectiveMtu = 1280.coerceAtMost(cfg.mtu.coerceIn(576, 9000))
        val ok = newEngine.start(descriptor, host, port, effectiveMtu, activeAttemptId.get(), hevSettings, cfg.hevUdpMode)
        stateMutex.withLock {
            if (ok) {
                hevEngine = newEngine
                lastHevUpstream = target
                DnsMap.clear()
                routingEngine?.clearCache()
                if (statsJob == null) startStatsJob()
            }
        }
    }

    private fun resolveEffectiveSocks(config: io.github.immaghzbad.aetherst.shared.model.AetherConfig, psiphonUrl: String?): Pair<String, Int> {
        val torPort = ActiveProxyProvider.torProxyUrl?.substringAfterLast(":")?.toIntOrNull()
        if (torPort != null) return "127.0.0.1" to torPort

        when (config.exitNodeMode) {
            ExitNodeMode.DIRECT -> {
                val isLegacyPsiphon = psiphonUrl?.contains("3080") == true || config.upstreamProxy.contains("3080") || config.chainProvider == ChainProvider.PSIPHON
                if (isLegacyPsiphon) {
                    val port = psiphonUrl?.substringAfterLast(":")?.toIntOrNull() ?: 3080
                    return "127.0.0.1" to port
                }
                val host = config.socksHost
                val port = config.socksPort.toIntOrNull() ?: 1819
                return host to port
            }
            ExitNodeMode.PSIPHON_POOL -> {
                val poolPort = psiphonUrl?.substringAfterLast(":")?.toIntOrNull()
                    ?: config.psiphonSocksPort.toIntOrNull()
                    ?: 10800
                return "127.0.0.1" to poolPort
            }
            ExitNodeMode.CUSTOM_WG -> {
                val localPort = 10805
                val wgCfg = config.customWgConfig
                if (wgCfg.isValid()) {
                    if (wgExitTunnel == null) {
                        val primaryPort = config.socksPort.toIntOrNull() ?: 1819
                        val tunnel = WireGuardExitTunnel(
                            config = wgCfg,
                            primarySocksHost = "127.0.0.1",
                            primarySocksPort = primaryPort,
                            localListenPort = localPort
                        )
                        tunnel.start()
                        wgExitTunnel = tunnel
                    }
                    return "127.0.0.1" to localPort
                } else {
                    LogRepository.w("[VpnService] Custom WG config invalid, falling back to direct primary socks")
                    return "127.0.0.1" to (config.socksPort.toIntOrNull() ?: 1819)
                }
            }
            ExitNodeMode.CUSTOM_SOCKS5 -> {
                val localPort = 10806
                if (config.customSocks5Host.isNotBlank()) {
                    if (customSocksRelay == null) {
                        val primaryPort = config.socksPort.toIntOrNull() ?: 1819
                        val relay = CustomSocks5Relay(
                            listenPort = localPort,
                            primarySocksHost = "127.0.0.1",
                            primarySocksPort = primaryPort,
                            remoteHost = config.customSocks5Host.trim(),
                            remotePort = config.customSocks5Port,
                            username = config.customSocks5Username,
                            password = config.customSocks5Password
                        )
                        relay.start()
                        customSocksRelay = relay
                    }
                    return "127.0.0.1" to localPort
                } else {
                    LogRepository.w("[VpnService] Custom SOCKS5 host blank, falling back to direct primary socks")
                    return "127.0.0.1" to (config.socksPort.toIntOrNull() ?: 1819)
                }
            }
        }
    }

    private fun closeVpnInterface(attemptId: Long) {
        vpnInterface?.let {
            runCatching { it.close() }
            vpnInterface = null
            LogRepository.i("[Tun] [attempt=$attemptId] Closed")
        }
    }

    private fun startStatsJob() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                updateTraffic()
            }
        }
    }

    private fun stopStatsJob() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun updateTraffic() {
        if (activeTunnelEngine == TunnelEngine.HEV_TUN2SOCKS) {
            hevEngine?.stats?.value?.let {
                getController().setTraffic(it.txBytes, it.rxBytes)
            }
        } else {
            socksBridge?.getStats()?.let {
                getController().setTraffic(it.txBytes, it.rxBytes)
            }
        }
    }

    private fun showInitialNotification() {
        val text = when (ConnectionController.status.value) {
            ConnectionStatus.RUNNING, ConnectionStatus.TUN_ACTIVE -> "VPN connected"
            ConnectionStatus.RECONNECTING -> "Reconnecting..."
            ConnectionStatus.STOPPING -> "Disconnecting..."
            ConnectionStatus.ERROR, ConnectionStatus.FAILED -> "Connection error"
            else -> "Connecting..."
        }
        try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try { stopSelf() } catch (_: Exception) {}
        }
    }

    private fun updateNotification() {
        val status = ConnectionController.status.value
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (status == ConnectionStatus.STOPPED) {
            manager.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val text = when (status) {
            ConnectionStatus.RUNNING, ConnectionStatus.TUN_ACTIVE -> "VPN connected"
            ConnectionStatus.STARTING, ConnectionStatus.VALIDATING, ConnectionStatus.DATAPLANE_VALIDATED, ConnectionStatus.SOCKS_READY -> "Connecting..."
            ConnectionStatus.RECONNECTING -> "Reconnecting..."
            ConnectionStatus.STOPPING -> "Disconnecting..."
            ConnectionStatus.ERROR, ConnectionStatus.FAILED -> "Connection error"
        }
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(statusText: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, AetherVpnService::class.java).apply { action = ACTION_STOP }, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AetherST Tunnel")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_stat_aether)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun showDisconnectionAlert(reason: String) {
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
            val reconnectIntent = PendingIntent.getService(
                this, 2,
                Intent(this, AetherVpnService::class.java).apply { action = ACTION_START },
                flags
            )

            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("⚠️ VPN Disconnected")
                .setContentText("Connection lost unexpectedly. Tap to reconnect.")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("AetherST Tunnel was disconnected unexpectedly.\nReason: $reason\n\nTap 'Reconnect' to restore your secure connection.")
                )
                .setSmallIcon(R.drawable.ic_stat_aether)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_popup_sync, "Reconnect", reconnectIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(0xFFFF3B30.toInt())
                .build()

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val statusChannel = NotificationChannel(CHANNEL_ID, "AetherST Tunnel", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "AetherST Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifications for unexpected disconnections"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(alertChannel)
    }

    override fun onDestroy() {
        autoReconnectJob?.cancel()
        scope.cancel()
        unregisterNetworkMonitor()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
