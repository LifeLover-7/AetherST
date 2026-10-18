package io.github.immaghzbad.aetherst.core

import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.platform.getSystemUtils
import io.github.immaghzbad.aetherst.shared.core.getBinaryManager
import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.data.PsiphonEgressRegistry
import io.github.immaghzbad.aetherst.shared.model.AetherConfig
import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PsiphonController {
    @Volatile private var psiphonPort: Int = 3080
    @Volatile private var running = false
    @Volatile private var connected = false
    private val isStarting = AtomicBoolean(false)
    private val lastEventTime = AtomicLong(0L)

    private var process: Process? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isSupported(config: AetherConfig): Boolean {
        return config.psiphonEnabled
    }

    fun getPort(): Int = psiphonPort

    fun getUpstreamProxy(): String {
        return "socks5://127.0.0.1:$psiphonPort"
    }

    private fun findFreePort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (_: Exception) { 3080 }
    }

    fun start(context: PlatformContext, config: AetherConfig, upstream: String? = null): Boolean {
        if (!isSupported(config)) return false
        if (running && process?.isAlive == true) return true
        if (isStarting.getAndSet(true)) return false

        return try {
            val baseDir = File(getSystemUtils(context).getFilesDir())
            val psiphonDir = File(baseDir, "psiphon")
            if (!psiphonDir.exists()) psiphonDir.mkdirs()

            val requested = config.psiphonSocksPort.toIntOrNull() ?: 3080
            val port = if (requested.toString() == config.socksPort) findFreePort() else requested
            psiphonPort = port

            val binManager = getBinaryManager(context)
            val binaryPath = binManager.prepareBinary("psiphon-tunnel-core.exe")

            copyServerEntriesIfAvailable(psiphonDir)

            val configFile = File(psiphonDir, "psiphon.config")
            val configJson = buildPsiphonConfig(psiphonDir, port, config.psiphonEgressRegion, upstream)
            configFile.writeText(configJson)

            LogRepository.i("Launching Psiphon core on port $port (upstream=${upstream ?: "direct"})...", "Psiphon")

            val pb = ProcessBuilder(binaryPath, "-config", configFile.absolutePath)
            pb.directory(psiphonDir)
            pb.redirectErrorStream(true)

            val proc = pb.start()
            process = proc
            running = true

            monitorJob?.cancel()
            monitorJob = scope.launch {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (isActive && reader.readLine().also { line = it } != null) {
                            val l = line ?: break
                            parsePsiphonOutput(context, l)
                        }
                    }
                } catch (_: Exception) {}
            }

            isStarting.set(false)
            true
        } catch (e: Exception) {
            isStarting.set(false)
            running = false
            connected = false
            LogRepository.e("Failed to start Psiphon on Windows: ${e.message}", "Psiphon")
            false
        }
    }

    private fun parsePsiphonOutput(context: PlatformContext, line: String) {
        val lower = line.lowercase()

        if (line.contains("ListeningSocksProxyPort", ignoreCase = true)) {
            val portMatch = Regex(""""port":\s*(\d+)""").find(line)
            val p = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: psiphonPort
            psiphonPort = p
            LogRepository.i("Psiphon listening on $p", "Psiphon")
        }

        if (line.contains("ActiveTunnel", ignoreCase = true) ||
            line.contains("Tunnels", ignoreCase = true) ||
            line.contains("tunnel connected", ignoreCase = true)) {
            connected = true
            lastEventTime.set(System.currentTimeMillis())
            LogRepository.i("Psiphon tunnel connected successfully", "Psiphon")
        }

        if (line.contains("AvailableEgressRegions", ignoreCase = true) || line.contains("regions", ignoreCase = true)) {
            val regions = Regex(""""([A-Z]{2})"""").findAll(line)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (regions.isNotEmpty()) {
                PsiphonEgressRegistry.setAvailableRegions(regions)
                runCatching { AetherConfigRepository.getInstance(getSettings(context)).cacheEgressRegions(regions) }
                LogRepository.i("Psiphon regions: ${regions.joinToString()}", "Psiphon")
            }
        }

        if (lower.contains("error") || lower.contains("fatal")) {
            LogRepository.w("[PsiphonCore] $line", "Psiphon")
        } else {
            LogRepository.d("[PsiphonCore] $line", "Psiphon")
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
          "EstablishTunnelTimeoutSeconds": 120,
          "DataRootDirectory": "$safeDir",
          "ClientVersion": "1",
          "TunnelProtocol": "",
          "RemoteServerListURL": "",
          "LocalSocksProxyPort": $port,
          "RemoteServerListSignaturePublicKey": "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=",
          "ServerEntrySignaturePublicKey": "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=",
          "ExchangeObfuscationKey": "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=",
          "EmitBytesTransferred": true,
          "DeviceRegion": "IR",
          "ConnectionWorkerPoolSize": 12$safeEgress$safeUpstream
        }
        """.trimIndent()
    }

    private fun copyServerEntriesIfAvailable(psiphonDir: File) {
        val target = File(psiphonDir, "server_entries.txt")
        if (target.exists() && target.length() > 0) return
        try {
            val stream = javaClass.getResourceAsStream("/server_entries.txt")
                ?: javaClass.getResourceAsStream("/bin/server_entries.txt")
                ?: javaClass.classLoader.getResourceAsStream("server_entries.txt")
            stream?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!running) return
        running = false
        connected = false
        monitorJob?.cancel()
        monitorJob = null

        Thread {
            try {
                process?.destroy()
                process = null
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                if (isWindows) {
                    ProcessBuilder("taskkill", "/F", "/T", "/IM", "psiphon-tunnel-core.exe")
                        .redirectErrorStream(true)
                        .start()
                }
            } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            name = "PsiphonKiller"
            start()
        }

        LogRepository.i("Psiphon stopped", "Psiphon")
    }

    fun isRunning(): Boolean = running && (process?.isAlive == true)

    fun isConnected(): Boolean = connected && isRunning()

    fun stableFor(graceMs: Long): Boolean = connected && isRunning() &&
            (System.currentTimeMillis() - lastEventTime.get()) >= graceMs
}
