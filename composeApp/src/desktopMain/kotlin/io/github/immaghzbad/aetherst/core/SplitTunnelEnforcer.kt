package io.github.immaghzbad.aetherst.shared.core

import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSystemUtils
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.model.AetherConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop implementation of the per-app "Block" split-tunnel mode.
 *
 * Unlike Bypass (which is destination based on Windows, see the Iran-direct preset and the
 * routing rules), a per-app BLOCK can be enforced reliably without a kernel driver: the
 * Windows Defender Firewall evaluates rules at the ALE layer, i.e. per process, so all
 * outbound traffic of the selected executables can be dropped regardless of the adapter it
 * would have used.
 *
 * Rules are tagged with a distinctive group name so they can always be removed again,
 * including leftovers from a previous crashed session (tracked through a marker file, so a
 * normal start never has to ask for elevation on a clean machine).
 */
object SplitTunnelEnforcer {

    private const val RULE_PREFIX = "AetherST SplitTunnel Block "
    private const val RULE_GROUP = "AetherST SplitTunnel"
    private const val APPLY_SCRIPT = "split-tunnel-apply.ps1"
    private const val CLEANUP_SCRIPT = "split-tunnel-cleanup.ps1"
    private const val MARKER_FILE = "split-tunnel-rules.active"
    private const val LOG_MAX_CHARS = 1500

    private val isRunning = AtomicBoolean(false)
    private val isPending = AtomicBoolean(false)
    @Volatile private var lastAppliedPaths: Set<String> = emptySet()

    /**
     * Firewall edits are serialized: a stop (cleanup) and the following start (apply) must
     * never race, otherwise the late cleanup would delete the freshly installed rules.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Aether-SplitTunnel").apply { isDaemon = true }
    }

    fun isActive(): Boolean = isRunning.get()

    /** Number of program paths that were handed to the firewall. */
    fun activeRuleCount(): Int = lastAppliedPaths.size

    /**
     * Installs one firewall rule per blocked executable. Runs in the background so that a
     * UAC prompt can never stall the connection flow, and never throws.
     */
    fun apply(config: AetherConfig) {
        if (!isWindows()) return
        val paths = resolveBlockedExecutables(config)
        lastAppliedPaths = paths
        if (paths.isEmpty()) {
            removeAll()
            return
        }
        isPending.set(true)
        runScriptAsync(buildApplyScript(paths), "apply", APPLY_SCRIPT) { log ->
            isPending.set(false)
            if (log != null && log.contains("block rule(s) active")) {
                isRunning.set(true)
                writeMarker()
                LogRepository.i("[Split] ${log.take(LOG_MAX_CHARS)}")
            } else {
                LogRepository.w("[Split] Block rules could not be confirmed (approve the elevation prompt to enforce them)")
            }
        }
    }

    /** Removes AetherST rules in the background; a no-op when nothing was ever installed. */
    fun removeAll() {
        if (!isWindows()) {
            isRunning.set(false)
            lastAppliedPaths = emptySet()
            return
        }
        // A queued apply must still be followed by a cleanup, otherwise its rules survive.
        if (!isRunning.get() && !isPending.get() && !markerExists()) {
            lastAppliedPaths = emptySet()
            return
        }
        isRunning.set(false)
        lastAppliedPaths = emptySet()
        runScriptAsync(CLEANUP_BODY, "cleanup", CLEANUP_SCRIPT) { log ->
            if (log != null) deleteMarker()
        }
    }

    /**
     * Synchronous variant for the JVM shutdown hook: rules are persistent, so they must be
     * gone even if the process is about to die.
     */
    fun removeAllBlocking() {
        if (!isWindows()) return
        if (!isRunning.get() && !isPending.get() && !markerExists()) return
        runCatching {
            runScript(CLEANUP_BODY, "cleanup-exit", CLEANUP_SCRIPT, timeoutSeconds = 12)
            deleteMarker()
        }
        isRunning.set(false)
        lastAppliedPaths = emptySet()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true

    private fun dataDir(): File {
        val dir = File(getSystemUtils(PlatformContext()).getFilesDir())
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun markerExists(): Boolean = runCatching { File(dataDir(), MARKER_FILE).exists() }.getOrDefault(false)

    private fun writeMarker() {
        runCatching { File(dataDir(), MARKER_FILE).writeText(System.currentTimeMillis().toString()) }
    }

    private fun deleteMarker() {
        runCatching { File(dataDir(), MARKER_FILE).delete() }
    }

    /**
     * Resolves the configured entries to absolute executable paths. Entries may be full
     * paths (desktop file picker), .lnk shortcuts (Start Menu scan) or bare exe names;
     * only resolvable .exe files are used.
     */
    private fun resolveBlockedExecutables(config: AetherConfig): Set<String> {
        val result = LinkedHashSet<String>()
        for (raw in config.blockedPackages) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val path = resolveExecutablePath(trimmed)
            if (path == null) {
                LogRepository.w("[Split] Skipping unresolvable block target: $trimmed")
                continue
            }
            result.add(path)
        }
        return result
    }

    private fun resolveExecutablePath(raw: String): String? {
        val direct = File(raw)
        if (direct.isFile) {
            return when {
                direct.extension.equals("exe", ignoreCase = true) -> direct.absolutePath
                direct.extension.equals("lnk", ignoreCase = true) ->
                    resolveLnkTarget(direct)?.takeIf { it.endsWith(".exe", ignoreCase = true) && File(it).isFile }
                else -> null
            }
        }
        if (!raw.contains('\\') && !raw.contains('/') && raw.endsWith(".exe", ignoreCase = true)) {
            return queryImagePath(raw)
        }
        return null
    }

    private fun resolveLnkTarget(lnk: File): String? = runCatching {
        val safe = lnk.absolutePath.replace("'", "''")
        val command = "(New-Object -ComObject WScript.Shell).CreateShortcut('$safe').TargetPath"
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).start()
        proc.waitFor(4, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }.getOrNull()

    /** Uses the process table to find the image path of a running exe by name. */
    private fun queryImagePath(exeName: String): String? = runCatching {
        val safe = exeName.replace("'", "''")
        val command = "Get-CimInstance Win32_Process -Filter \"Name='$safe'\" | " +
            "Select-Object -First 1 -ExpandProperty ExecutablePath"
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).start()
        proc.waitFor(4, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }.getOrNull()

    private fun buildApplyScript(paths: Set<String>): String {
        val pathArguments = paths.joinToString(",\n") { "'" + it.replace("'", "''") + "'" }
        return """
            ${'$'}ErrorActionPreference = 'Continue'
            ${'$'}prefix = '$RULE_PREFIX'
            ${'$'}paths = @(
            $pathArguments
            )
            # فایروال ویندوز در لایه ALE بر اساس برنامه فیلتر می‌کند؛ مستقل از آداپتور تونل.
            Get-NetFirewallRule -Group '$RULE_GROUP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
            ${'$'}installed = 0
            ${'$'}index = 0
            foreach (${'$'}p in ${'$'}paths) {
                ${'$'}index++
                if (-not (Test-Path -LiteralPath ${'$'}p)) { continue }
                ${'$'}tag = ${'$'}index.ToString('000')
                try {
                    New-NetFirewallRule -DisplayName (${'$'}prefix + ${'$'}tag) -Group '$RULE_GROUP' -Direction Outbound -Program ${'$'}p -Action Block -Profile Any -Enabled True -ErrorAction Stop | Out-Null
                    ${'$'}installed++
                } catch { }
            }
            Write-Output ("AetherST split-tunnel: " + ${'$'}installed + " of " + (${'$'}paths.Count) + " block rule(s) active")
        """.trimIndent() + "\n"
    }

    private val CLEANUP_BODY: String = """
        ${'$'}ErrorActionPreference = 'Continue'
        # فقط قوانین ساخته‌شده توسط AetherST حذف می‌شوند، نه قوانین فایروال کاربر.
        Get-NetFirewallRule -Group '$RULE_GROUP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
        Get-NetFirewallRule -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.DisplayName -like '$RULE_PREFIX*' } | Remove-NetFirewallRule -ErrorAction SilentlyContinue
        Write-Output 'AetherST split-tunnel: cleanup done'
    """.trimIndent() + "\n"

    private fun runScriptAsync(script: String, logSuffix: String, scriptName: String, onDone: (String?) -> Unit) {
        runCatching {
            worker.execute {
                val log = runCatching { runScript(script, logSuffix, scriptName) }.getOrNull()
                runCatching { onDone(log) }
            }
        }
    }

    /**
     * Writes and executes a PowerShell script with administrator rights; returns the
     * captured output. When the app is already elevated (the normal case, because the TUN
     * helper needs it) the script runs inline, otherwise it asks for elevation via UAC.
     */
    private fun runScript(
        script: String,
        logSuffix: String,
        scriptName: String,
        timeoutSeconds: Long = 45
    ): String? {
        val dir = dataDir()
        val scriptFile = File(dir, scriptName)
        scriptFile.writeText(script)
        val logFile = File(dir, "split-tunnel-$logSuffix-${System.currentTimeMillis()}.log")
        val elevated = Elevation.isElevated()
        val safeScript = scriptFile.absolutePath.replace("'", "''")
        val safeLog = logFile.absolutePath.replace("'", "''")
        val command = if (elevated) {
            arrayOf(
                "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-Command", "& '$safeScript' *> '$safeLog'"
            )
        } else {
            arrayOf(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Start-Process -FilePath 'powershell' -WindowStyle Hidden -Verb RunAs -Wait " +
                    "-ArgumentList '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass'," +
                    "'-WindowStyle','Hidden','-File','$safeScript'"
            )
        }
        val proc = ProcessBuilder(*command).redirectErrorStream(true).start()
        proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        // The elevated child writes to the log; poll briefly so the caller sees the result.
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (logFile.exists() && logFile.length() > 0) {
                val text = runCatching { logFile.readText().trim() }.getOrDefault("")
                if (text.isNotEmpty()) return text
            }
            Thread.sleep(250)
        }
        return null
    }
}
