package io.github.immaghzbad.aetherst.core

import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.platform.getSystemUtils
import io.github.immaghzbad.aetherst.shared.core.getBinaryManager
import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.data.PsiphonEgressRegistry
import io.github.immaghzbad.aetherst.shared.model.AetherConfig
import io.github.immaghzbad.aetherst.shared.model.ExitNodeMode
import io.github.immaghzbad.aetherst.shared.net.SocksPoolLoadBalancer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object PsiphonController {
    const val POOL_SIZE = 4
    const val BASE_PORT = 10801

    @Volatile private var psiphonPort: Int = 10800
    @Volatile private var running = false
    @Volatile private var connected = false
    private val isStarting = AtomicBoolean(false)
    private val lastEventTime = AtomicLong(0L)
    private val connectedCount = AtomicInteger(0)

    private val processes = CopyOnWriteArrayList<Process>()
    private val monitorJobs = CopyOnWriteArrayList<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loadBalancer: SocksPoolLoadBalancer? = null

    fun isSupported(config: AetherConfig): Boolean = config.psiphonEnabled || config.exitNodeMode == ExitNodeMode.PSIPHON_POOL

    fun getPort(): Int = psiphonPort

    fun getUpstreamProxy(): String = "socks5://127.0.0.1:$psiphonPort"

    fun start(context: PlatformContext, config: AetherConfig, upstream: String? = null): Boolean {
        if (!isSupported(config)) return false
        if (running && processes.any { it.isAlive }) return true
        if (isStarting.getAndSet(true)) return false

        return try {
            val baseDir = File(getSystemUtils(context).getFilesDir())
            val psiphonPoolDir = File(baseDir, "psiphon_pool")
            if (!psiphonPoolDir.exists()) psiphonPoolDir.mkdirs()

            val port = config.psiphonSocksPort.toIntOrNull() ?: 10800
            psiphonPort = port

            val binManager = getBinaryManager(context)
            val binaryPath = binManager.prepareBinary("psiphon-tunnel-core.exe")

            val poolPorts = (0 until POOL_SIZE).map { BASE_PORT + it }
            connectedCount.set(0)
            processes.clear()
            monitorJobs.forEach { it.cancel() }
            monitorJobs.clear()

            // 1. Start Round-Robin SOCKS5 Load Balancer
            val lb = SocksPoolLoadBalancer(
                listenHost = "127.0.0.1",
                listenPort = psiphonPort,
                upstreamPorts = poolPorts
            )
            if (!lb.start()) {
                LogRepository.e("[PsiphonPool] Failed to start desktop load balancer on $psiphonPort", "Psiphon")
            }
            loadBalancer = lb

            // 2. Start pool of 4 parallel processes
            for (i in 0 until POOL_SIZE) {
                val instPort = BASE_PORT + i
                val instDir = File(psiphonPoolDir, "inst_$i")
                if (!instDir.exists()) instDir.mkdirs()

                copyServerEntries(instDir)

                val serverListFile = File(instDir, "server_entries.txt")
                val configFile = File(instDir, "psiphon.config")
                val configJson = buildPsiphonConfig(instDir, instPort, config.psiphonEgressRegion, upstream)
                configFile.writeText(configJson)

                val command = mutableListOf(binaryPath, "-config", configFile.absolutePath)
                if (serverListFile.exists() && serverListFile.length() > 0) {
                    command.add("-serverList")
                    command.add(serverListFile.absolutePath)
                }

                val logFile = File(instDir, "psiphon.log")
                val pb = ProcessBuilder(command)
                pb.directory(instDir)
                pb.environment()["GOMEMLIMIT"] = "32MiB"
                pb.environment()["GOGC"] = "50"
                pb.redirectErrorStream(true)

                val proc = pb.start()
                processes.add(proc)

                val job = scope.launch {
                    try {
                        proc.inputStream.bufferedReader().use { reader ->
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                logFile.appendText(line + "\n")
                                parsePsiphonOutput(context, line)
                            }
                        }
                    } catch (_: Exception) {}
                }
                monitorJobs.add(job)
            }

            running = processes.isNotEmpty()
            LogRepository.i("[PsiphonPool] Desktop launched $POOL_SIZE parallel cores (ports: ${poolPorts.joinToString()}) with OSSH lock and LB on $psiphonPort", "Psiphon")
            isStarting.set(false)
            true
        } catch (e: Exception) {
            isStarting.set(false)
            running = false
            connected = false
            LogRepository.e("[PsiphonPool] Launch error: ${e.message}", "Psiphon")
            false
        }
    }

    private fun parsePsiphonOutput(context: PlatformContext, line: String) {
        if (line.contains("ActiveTunnel", ignoreCase = true) ||
            line.contains("Tunnels", ignoreCase = true) ||
            line.contains("tunnel connected", ignoreCase = true)) {
            connected = true
            connectedCount.incrementAndGet()
            lastEventTime.set(System.currentTimeMillis())
            LogRepository.i("[PsiphonPool] Core connected (${connectedCount.get()}/$POOL_SIZE active)", "Psiphon")
        }

        if (line.contains("AvailableEgressRegions", ignoreCase = true) || line.contains("regions", ignoreCase = true)) {
            val regions = Regex(""""([A-Z]{2})"""").findAll(line)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (regions.isNotEmpty()) {
                PsiphonEgressRegistry.setAvailableRegions(regions)
                runCatching { AetherConfigRepository.getInstance(getSettings(context)).cacheEgressRegions(regions) }
            }
        }
    }

    private fun buildPsiphonConfig(dir: File, port: Int, egressRegion: String, upstream: String?): String {
        val safeDir = dir.absolutePath.replace("\\", "\\\\")
        val safeEgress = if (egressRegion.isNotEmpty()) ""","EgressRegion":"$egressRegion"""" else ""
        val safeUpstream = if (!upstream.isNullOrEmpty()) ""","UpstreamProxyURL":"${upstream.replace("\"", "\\\"")}"""" else ""

        return """
        {
          "PropagationChannelId": "FFFFFFFFFFFFFFFF",
          "SponsorId": "1111111111111111",
          "EstablishTunnelTimeoutSeconds": 300,
          "DataRootDirectory": "$safeDir",
          "ClientVersion": "1",
          "TunnelProtocol": "OSSH",
          "TunnelProtocols": ["OSSH", "QUIC-OSSH"],
          "RemoteServerListURL": "",
          "LocalSocksProxyPort": $port,
          "RemoteServerListSignaturePublicKey": "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=",
          "ServerEntrySignaturePublicKey": "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=",
          "ExchangeObfuscationKey": "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=",
          "EmitBytesTransferred": true,
          "ConnectionWorkerPoolSize": 12$safeEgress$safeUpstream
        }
        """.trimIndent()
    }

    private fun copyServerEntries(psiphonDir: File) {
        val target = File(psiphonDir, "server_entries.txt")
        if (target.exists() && target.length() > 10) return
        try {
            val stream = javaClass.getResourceAsStream("/server_entries.txt")
                ?: javaClass.getResourceAsStream("/bin/server_entries.txt")
                ?: javaClass.classLoader.getResourceAsStream("server_entries.txt")
                ?: javaClass.classLoader.getResourceAsStream("bin/server_entries.txt")
            stream?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!running && processes.isEmpty()) return
        running = false
        connected = false
        connectedCount.set(0)
        monitorJobs.forEach { it.cancel() }
        monitorJobs.clear()
        loadBalancer?.stop()
        loadBalancer = null

        Thread {
            for (p in processes) {
                try { p.destroy() } catch (_: Exception) {}
            }
            processes.clear()
            if (System.getProperty("os.name").lowercase().contains("win")) {
                try {
                    ProcessBuilder("taskkill", "/F", "/T", "/IM", "psiphon-tunnel-core.exe")
                        .redirectErrorStream(true).start()
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }

        LogRepository.i("[PsiphonPool] Desktop stopped all instances and load balancer", "Psiphon")
    }

    fun isRunning(): Boolean = running && processes.any { it.isAlive }

    fun isConnected(): Boolean = connected && isRunning() && connectedCount.get() > 0

    fun stableFor(graceMs: Long): Boolean = connected && isRunning() &&
            (System.currentTimeMillis() - lastEventTime.get()) >= graceMs

    suspend fun probeListening(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", psiphonPort), 1000)
                true
            }
        }.getOrDefault(false)
    }
}
