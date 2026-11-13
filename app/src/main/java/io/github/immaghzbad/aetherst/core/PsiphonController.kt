package io.github.immaghzbad.aetherst.core

import android.content.Context
import android.net.VpnService
import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.data.PsiphonEgressRegistry
import io.github.immaghzbad.aetherst.shared.model.AetherConfig
import io.github.immaghzbad.aetherst.shared.model.ExitNodeMode
import io.github.immaghzbad.aetherst.shared.net.SocksPoolLoadBalancer
import java.io.File
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object PsiphonController {
    const val POOL_SIZE = 2
    const val BASE_PORT = 10801

    @Volatile private var psiphonPort: Int = 10800
    @Volatile private var running = false
    @Volatile private var connected = false
    private val tunnels = CopyOnWriteArrayList<Any>()
    private val connectedCount = AtomicInteger(0)
    private val lastEventTime = AtomicLong(0L)
    @Volatile private var vpnServiceRef: WeakReference<VpnService>? = null
    @Volatile private var loadBalancer: SocksPoolLoadBalancer? = null

    fun setVpnService(service: VpnService) {
        vpnServiceRef = WeakReference(service)
    }

    fun isSupported(config: AetherConfig): Boolean {
        return config.psiphonEnabled || config.exitNodeMode == ExitNodeMode.PSIPHON_POOL
    }

    fun getUpstreamProxy(): String {
        return "socks5://127.0.0.1:$psiphonPort"
    }

    private fun findFreePort(preferred: Int = 10800): Int {
        return try {
            ServerSocket(preferred).use { it.localPort }
        } catch (_: Exception) {
            try { ServerSocket(0).use { it.localPort } } catch (_: Exception) { preferred }
        }
    }

    fun start(context: Context, config: AetherConfig, upstream: String? = null): Boolean {
        if (!isSupported(config)) return false
        stop()

        return try {
            val basePoolDir = File(context.filesDir, "psiphon_pool")
            if (!basePoolDir.exists()) basePoolDir.mkdirs()

            val requested = config.psiphonSocksPort.toIntOrNull() ?: 10800
            psiphonPort = if (requested.toString() == config.socksPort) findFreePort(10800) else requested

            val poolPorts = (0 until POOL_SIZE).map { BASE_PORT + it }
            val serverEntries = try {
                context.assets.open("server_entries.txt").bufferedReader().readText().trim()
            } catch (_: Exception) { "" }

            val clazz = Class.forName("ca.psiphon.PsiphonTunnel")
            val hostServiceClass = Class.forName("ca.psiphon.PsiphonTunnel\$HostService")
            val hostLoggerClass = Class.forName("ca.psiphon.PsiphonTunnel\$HostLogger")
            val hostLoaderClass = Class.forName("ca.psiphon.PsiphonTunnel\$HostLibraryLoader")

            connectedCount.set(0)
            tunnels.clear()

            // 1. Initialize and start the SOCKS5 Round-Robin Load Balancer
            val lb = SocksPoolLoadBalancer(
                listenHost = "127.0.0.1",
                listenPort = psiphonPort,
                upstreamPorts = poolPorts
            )
            if (!lb.start()) {
                LogRepository.e("[PsiphonPool] Failed to start load balancer on port $psiphonPort", "Psiphon")
            }
            loadBalancer = lb

            // 2. Launch pool of 4 parallel Psiphon instances (ports 10801..10804)
            for (i in 0 until POOL_SIZE) {
                val instPort = BASE_PORT + i
                val instDir = File(basePoolDir, "inst_$i")
                if (!instDir.exists()) instDir.mkdirs()

                val instIndex = i
                val hostService = java.lang.reflect.Proxy.newProxyInstance(
                    hostServiceClass.classLoader,
                    arrayOf(hostServiceClass, hostLoggerClass, hostLoaderClass)
                ) { _, method, args ->
                    when (method.name) {
                        "getContext" -> context
                        "getPsiphonConfig" -> buildPsiphonConfig(instDir, instPort, config.psiphonEgressRegion, upstream)
                        "onListeningSocksProxyPort" -> {
                            val p = args?.get(0) as? Int ?: instPort
                            LogRepository.i("[PsiphonPool#$instIndex] Listening on port $p", "Psiphon")
                            null
                        }
                        "onConnected" -> {
                            val c = connectedCount.incrementAndGet()
                            connected = true
                            lastEventTime.set(System.currentTimeMillis())
                            LogRepository.i("[PsiphonPool#$instIndex] Connected ($c/$POOL_SIZE active)", "Psiphon")
                            null
                        }
                        "onConnecting" -> {
                            lastEventTime.set(System.currentTimeMillis())
                            null
                        }
                        "onAvailableEgressRegions" -> {
                            val list = (args?.get(0) as? List<*>)?.mapNotNull { it?.toString()?.trim()?.uppercase() }?.filter { it.matches(Regex("^[A-Z]{2}$")) } ?: emptyList()
                            if (list.isNotEmpty()) {
                                PsiphonEgressRegistry.setAvailableRegions(list)
                                runCatching { AetherConfigRepository.getInstance(getSettings(PlatformContext(context))).cacheEgressRegions(list) }
                            }
                            null
                        }
                        "bindToDevice" -> {
                            val fd = (args?.get(0) as? Long)?.toInt() ?: (args?.get(0) as? Int) ?: -1
                            if (fd > 0) {
                                vpnServiceRef?.get()?.protect(fd) ?: false
                            } else {
                                false
                            }
                            null
                        }
                        else -> null
                    }
                }

                val newTunnel = clazz.getMethod("newPsiphonTunnel", hostServiceClass).invoke(null, hostService)
                if (newTunnel != null) {
                    val startMethod = clazz.getMethod("startTunneling", String::class.java)
                    startMethod.invoke(newTunnel, serverEntries)
                    val setVpnMode = try { clazz.getMethod("setVpnMode", Boolean::class.javaPrimitiveType) } catch (_: Exception) { null }
                    setVpnMode?.invoke(newTunnel, false)
                    tunnels.add(newTunnel)
                }
            }

            running = tunnels.isNotEmpty()
            LogRepository.i("[PsiphonPool] Launched $POOL_SIZE parallel instances on ports ${poolPorts.joinToString()} with OSSH protocol lock and LB on $psiphonPort", "Psiphon")
            true
        } catch (e: Exception) {
            val reason = e.message ?: "unknown error"
            LogRepository.w("[PsiphonPool] Library not found or start failed ($reason)", "Psiphon")
            running = false
            connected = false
            false
        }
    }

    private fun buildPsiphonConfig(dir: File, port: Int, egressRegion: String = "", upstream: String? = null): String {
        return try {
            val json = org.json.JSONObject()
            json.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            json.put("SponsorId", "1111111111111111")
            json.put("EgressRegion", egressRegion)
            json.put("EstablishTunnelTimeoutSeconds", 300)
            json.put("DataRootDirectory", dir.absolutePath)
            json.put("ClientVersion", "1")
            json.put("TunnelProtocol", "")
            json.put("RemoteServerListURL", "")
            json.put("LocalSocksProxyPort", port)
            if (!upstream.isNullOrEmpty()) json.put("UpstreamProxyURL", upstream)
            json.put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=")
            json.put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
            json.put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")
            json.put("EmitBytesTransferred", true)
            // DeviceRegion omitted to prevent region restriction
            json.put("ConnectionWorkerPoolSize", 12)
            json.toString()
        } catch (_: Exception) {
            val safeDir = dir.absolutePath.replace("\\", "\\\\")
            "{\"LocalSocksProxyPort\":$port,\"DataRootDirectory\":\"$safeDir\",\"TunnelProtocol\":\"\"}"
        }
    }

    fun stop() {
        if (!running && tunnels.isEmpty()) return
        for (tunnel in tunnels) {
            try {
                val m = tunnel.javaClass.getMethod("stop")
                m.invoke(tunnel)
            } catch (_: Exception) {}
        }
        tunnels.clear()
        loadBalancer?.stop()
        loadBalancer = null
        running = false
        connected = false
        connectedCount.set(0)
        vpnServiceRef?.clear()
        vpnServiceRef = null
        LogRepository.i("[PsiphonPool] Stopped all instances and load balancer", "Psiphon")
    }

    fun isRunning(): Boolean {
        if (!running) return false
        return tunnels.any { tunnel ->
            try {
                val m = tunnel.javaClass.getMethod("isRunning")
                (m.invoke(tunnel) as? Boolean) == true
            } catch (_: Exception) { false }
        }
    }

    fun isConnected(): Boolean = connected && running && connectedCount.get() > 0

    fun stableFor(graceMs: Long): Boolean = isConnected() &&
            (System.currentTimeMillis() - lastEventTime.get()) >= graceMs
}
