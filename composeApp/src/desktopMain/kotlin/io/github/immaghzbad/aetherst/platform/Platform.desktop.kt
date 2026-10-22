package io.github.immaghzbad.aetherst.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import io.github.immaghzbad.aetherst.shared.model.AppInfo
import io.github.immaghzbad.aetherst.shared.model.SplitTunnelAppManager
import io.github.immaghzbad.aetherst.shared.core.ConnectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

actual class PlatformContext

class DesktopVpnController(private val context: PlatformContext) : VpnController {
    private val connectionController get() = ConnectionController.getImpl(context)
    override fun startVpn() = connectionController.start()
    override fun stopVpn() = connectionController.stop()
    override fun restartVpn() { connectionController.stop(); connectionController.start() }
    override fun startProxy() = connectionController.start()
    override fun stopProxy() = connectionController.stop()
    override fun restartProxy() { connectionController.stop(); connectionController.start() }
    override fun submitLoginCode(code: String) = ConnectionController.submitLoginCode(code)
    override fun prepareVpn(onPermissionRequired: () -> Unit): Boolean = true
    override fun isVpnPrepared(): Boolean = true
}

class DesktopTrafficProvider : TrafficProvider {
    private var cachedTx = 0L
    private var cachedRx = 0L
    override fun getTxBytes(): Long { updateStats(); return cachedTx }
    override fun getRxBytes(): Long { updateStats(); return cachedRx }

    private fun updateStats() {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (isWindows) {
                val process = ProcessBuilder("netstat", "-e").start()
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            val rxStr = parts[1].replace(Regex("[^0-9]"), "")
                            val txStr = parts[2].replace(Regex("[^0-9]"), "")
                            if (rxStr.isNotEmpty() && txStr.isNotEmpty()) {
                                val rx = rxStr.toLongOrNull() ?: 0L
                                val tx = txStr.toLongOrNull() ?: 0L
                                if (rx > 0 || tx > 0) { cachedRx = rx; cachedTx = tx; break }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

class DesktopAppInfoProvider : AppInfoProvider {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val filesDir: String by lazy { DesktopSystemUtils().getFilesDir() }
    private val customAppsFile: File by lazy { File(filesDir, "custom_split_apps.txt") }
    private val removedAppsFile: File by lazy { File(filesDir, "removed_split_apps.txt") }

    init {
        loadSavedCustomAndRemovedApps()
        SplitTunnelAppManager.onPickDesktopApp = { onResult ->
            ioScope.launch {
                val picked = pickWindowsAppDialog()
                if (picked != null) {
                    saveCustomApp(picked)
                }
                onResult(picked)
            }
        }
        SplitTunnelAppManager.onRemoveApp = { packageName ->
            ioScope.launch { removeAppFromList(packageName) }
        }
        SplitTunnelAppManager.onRestoreRemovedApps = {
            ioScope.launch { restoreAllRemovedApps() }
        }
    }

    private fun loadSavedCustomAndRemovedApps() {
        val customList = mutableListOf<AppInfo>()
        if (customAppsFile.exists()) {
            runCatching {
                customAppsFile.readLines().forEach { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        customList.add(AppInfo(parts[0].trim(), parts[1].trim(), parts[1].trim(), false))
                    }
                }
            }
        }
        val removedSet = mutableSetOf<String>()
        if (removedAppsFile.exists()) {
            runCatching {
                removedAppsFile.readLines().filter { it.isNotBlank() }.forEach { removedSet.add(it.trim()) }
            }
        }
        SplitTunnelAppManager.setState(customList, removedSet)
    }

    private fun saveCustomApp(app: AppInfo) {
        val currentCustom = SplitTunnelAppManager.customApps.value.toMutableList()
        val currentRemoved = SplitTunnelAppManager.removedPackages.value.toMutableSet()
        currentRemoved.remove(app.packageName)
        if (currentCustom.none { it.packageName.equals(app.packageName, ignoreCase = true) }) {
            currentCustom.add(0, app)
        }
        persistState(currentCustom, currentRemoved)
    }

    private fun removeAppFromList(packageName: String) {
        val currentCustom = SplitTunnelAppManager.customApps.value.filterNot {
            it.packageName.equals(packageName, ignoreCase = true)
        }
        val currentRemoved = SplitTunnelAppManager.removedPackages.value.toMutableSet()
        currentRemoved.add(packageName)
        persistState(currentCustom, currentRemoved)
    }

    private fun restoreAllRemovedApps() {
        val currentCustom = SplitTunnelAppManager.customApps.value
        persistState(currentCustom, emptySet())
    }

    private fun persistState(custom: List<AppInfo>, removed: Set<String>) {
        runCatching {
            customAppsFile.writeText(custom.joinToString("\n") { "${it.name}|${it.packageName}" })
            removedAppsFile.writeText(removed.joinToString("\n"))
        }
        SplitTunnelAppManager.setState(custom, removed)
    }

    private fun pickWindowsAppDialog(): AppInfo? {
        return try {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, "انتخاب فایل اجرایی برنامه (*.exe)", java.awt.FileDialog.LOAD)
            fd.file = "*.exe;*.lnk"
            fd.isVisible = true
            val dir = fd.directory ?: return null
            val file = fd.file ?: return null
            val selectedFile = File(dir, file)
            if (!selectedFile.exists()) return null

            val resolvedPath = if (selectedFile.extension.equals("lnk", ignoreCase = true)) {
                resolveLnkTarget(selectedFile.absolutePath) ?: selectedFile.absolutePath
            } else {
                selectedFile.absolutePath
            }
            val displayName = selectedFile.nameWithoutExtension
                .replace("-", " ")
                .replace("_", " ")
                .trim()
                .ifEmpty { File(resolvedPath).nameWithoutExtension }

            AppInfo(
                name = displayName,
                packageName = resolvedPath,
                icon = resolvedPath,
                isSystemApp = false
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        loadSavedCustomAndRemovedApps()
        val apps = mutableListOf<AppInfo>()
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) return@withContext emptyList()
            val seen = mutableSetOf<String>()
            fun isSystemApp(name: String, path: String, publisher: String? = null): Boolean {
                val n = name.lowercase()
                val p = path.lowercase()
                val pub = publisher?.lowercase() ?: ""
                return n.contains("microsoft") || n.contains("windows") || n.contains("visual c++") || n.contains(".net") ||
                       p.contains("windows\\system") || p.contains("windowsapps") || p.contains("\\windows\\") ||
                       pub.contains("microsoft")
            }
            fun addApp(name: String, path: String, icon: String? = null, publisher: String? = null) {
                val key = name.lowercase().trim()
                if (key.isEmpty() || key.contains("uninstall") || key.contains("help") || key.contains("redistributable") || key.contains("update helper")) return
                if (seen.add(key)) {
                    val sys = isSystemApp(name, path, publisher)
                    apps.add(AppInfo(name.trim(), path, icon ?: path, sys))
                }
            }
            val startMenuPaths = listOf(
                File(System.getenv("ProgramData") ?: "C:\\ProgramData", "Microsoft\\Windows\\Start Menu\\Programs"),
                File(System.getProperty("user.home"), "AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs")
            )
            for (path in startMenuPaths) {
                if (path.exists()) {
                    path.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.equals("lnk", true) }.forEach { file ->
                        val name = file.nameWithoutExtension.trim()
                        if (name.isNotEmpty()) {
                            addApp(name, file.absolutePath, file.absolutePath)
                        }
                    }
                }
            }
            try {
                val psScript = """
                    ${'$'}keys = @('HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*','HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*')
                    Get-ItemProperty ${'$'}keys -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.DisplayName -and ${'$'}_.SystemComponent -ne 1 -and -not ${'$'}_.ParentKeyName -and ${'$'}_.DisplayName -notmatch 'Update|Redistributable|Help' } | ForEach-Object { "${'$'}(${'$'}_.DisplayName)|${'$'}(${'$'}_.DisplayIcon)|${'$'}(${'$'}_.InstallLocation)|${'$'}(${'$'}_.Publisher)" }
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", psScript).start()
                val lines = proc.inputStream.bufferedReader().readLines()
                proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parts = line.split("|", limit = 4)
                    val displayName = parts.getOrNull(0)?.trim() ?: continue
                    if (displayName.isEmpty()) continue
                    val displayIconRaw = parts.getOrNull(1)?.trim() ?: ""
                    val installLoc = parts.getOrNull(2)?.trim() ?: ""
                    val publisher = parts.getOrNull(3)?.trim()
                    val displayIcon = displayIconRaw.split(",")[0].trim().removeSurrounding("\"")
                    val path = when {
                        displayIcon.isNotEmpty() && File(displayIcon).exists() -> displayIcon
                        installLoc.isNotEmpty() && File(installLoc).exists() -> {
                            val exe = File(installLoc).listFiles()?.firstOrNull { it.isFile && it.extension.equals("exe", true) }?.absolutePath
                            exe ?: installLoc
                        }
                        else -> displayName
                    }
                    addApp(displayName, path, if (File(path).exists()) path else null, publisher)
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        if (apps.isEmpty()) {
            apps.add(AppInfo("Web Browser (Default)", "browser", null, false))
            apps.add(AppInfo("System Proxy (Global)", "all", null, true))
        }
        apps.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
    }
}

class DesktopSystemUtils : SystemUtils {
    override fun isBatteryOptimized(): Boolean = false
    override fun isNotificationPermissionGranted(): Boolean = true
    override fun getFilesDir(): String {
        val appName = "AetherST-Tunnel"
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val baseDir = if (isWindows) System.getenv("AppData") ?: System.getProperty("user.home") else System.getProperty("user.home")
        val dir = File(baseDir, appName)
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
    override fun getCacheDir(): String = System.getProperty("java.io.tmpdir")
    override fun getPackageName(): String = "io.github.immaghzbad.aetherst"
    override fun getAppVersion(): String {
        return try {
            val props = java.util.Properties()
            val stream = this::class.java.classLoader.getResourceAsStream("app.properties")
            if (stream != null) { stream.use { props.load(it) }; props.getProperty("app.version", "1.1.1") } else "1.1.1"
        } catch (_: Exception) { "1.1.1" }
    }
    override fun getAppVersionCode(): Int {
        return try {
            val props = java.util.Properties()
            val stream = this::class.java.classLoader.getResourceAsStream("app.properties")
            if (stream != null) { stream.use { props.load(it) }; props.getProperty("app.version_code", props.getProperty("app.versionCode", "1")).toIntOrNull() ?: 1 } else 1
        } catch (_: Exception) { 1 }
    }
    override fun exitApp() {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "AetherST")
            if (tempDir.exists()) tempDir.walkBottomUp().forEach { it.delete() }
        } catch (_: Exception) {}
        exitProcess(0)
    }
    override fun readLastCrashLog(): String? = runCatching {
        val file = File(System.getProperty("java.io.tmpdir"), "last_crash.log")
        if (file.exists()) file.readText() else null
    }.getOrNull()
    override fun clearCrashLog() { runCatching { File(System.getProperty("java.io.tmpdir"), "last_crash.log").delete() } }
    override fun copyToClipboard(text: String) {
        val selection = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
    override fun requestNotificationPermission() {}
    override fun requestBatteryOptimization() {}
    override fun openVpnSettings() {}

    override fun exportFile(fileName: String, content: String, onResult: (Boolean) -> Unit) {
        try {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, "Save Backup", java.awt.FileDialog.SAVE)
            fd.file = fileName
            fd.isVisible = true
            val dir = fd.directory
            val file = fd.file
            if (dir != null && file != null) { File(dir, file).writeText(content); onResult(true) } else onResult(false)
        } catch (_: Exception) { onResult(false) }
    }

    override fun importFile(onResult: (String?) -> Unit) {
        try {
            val fc = javax.swing.JFileChooser()
            fc.dialogTitle = "Select Backup File"
            fc.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
            fc.isAcceptAllFileFilterUsed = true
            fc.addChoosableFileFilter(object : javax.swing.filechooser.FileFilter() {
                override fun accept(f: File): Boolean = f.isDirectory || f.name.endsWith(".astf", ignoreCase = true)
                override fun getDescription(): String = "AetherST files (*.astf)"
            })
            fc.fileFilter = fc.choosableFileFilters.last()
            val result = fc.showOpenDialog(null)
            if (result == javax.swing.JFileChooser.APPROVE_OPTION) onResult(fc.selectedFile.readText()) else onResult(null)
        } catch (_: Exception) { onResult(null) }
    }

    override fun shareFile(fileName: String, content: String) { exportFile(fileName, content) {} }

    override fun readInternalAsset(fileName: String): String? = runCatching {
        val classLoader = Thread.currentThread().contextClassLoader ?: this::class.java.classLoader
        classLoader.getResourceAsStream(fileName)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    override fun setSystemProxy(host: String, port: Int) {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) return
            val proxyStr = "http=$host:$port;https=$host:$port"
            val regPath = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            val bypass = "<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*"
            ProcessBuilder("reg", "add", regPath, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            ProcessBuilder("reg", "add", regPath, "/v", "ProxyServer", "/t", "REG_SZ", "/d", proxyStr, "/f").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            ProcessBuilder("reg", "add", regPath, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", bypass, "/f").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val psCommand = "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WinInet { [DllImport(\"wininet.dll\")] public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength); }'; [WinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null; [WinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null"
            ProcessBuilder("powershell", "-WindowStyle", "Hidden", "-Command", psCommand).start().waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    override fun clearSystemProxy() {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) return
            val regPath = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            ProcessBuilder("reg", "add", regPath, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val psCommand = "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WinInet { [DllImport(\"wininet.dll\")] public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength); }'; [WinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null; [WinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null"
            ProcessBuilder("powershell", "-WindowStyle", "Hidden", "-Command", psCommand).start().waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    override fun setSystemDns(dnsList: String) {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) return
            val servers = dnsList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (servers.isEmpty()) return
            val serverArray = servers.joinToString(",") { "'$it'" }
            val backupFile = File(getFilesDir(), "dns_backup.txt")
            try {
                val psBackup = "Get-NetAdapter | Where-Object Status -eq 'Up' | ForEach-Object { \$idx=\$_.InterfaceIndex; \$dns=(Get-DnsClientServerAddress -InterfaceIndex \$idx -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses -join ','; \"\$idx=\$dns\" } | Out-File -Encoding utf8 \"${backupFile.absolutePath.replace("\\", "/")}\""
                ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psBackup).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {}
            val psSet = "Get-NetAdapter | Where-Object Status -eq 'Up' | ForEach-Object { try { Set-DnsClientServerAddress -InterfaceIndex \$_.InterfaceIndex -ServerAddresses @($serverArray) -ErrorAction SilentlyContinue } catch {} }; Clear-DnsClientCache -ErrorAction SilentlyContinue"
            ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psSet).start().waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    override fun clearSystemDns() {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) return
            val backupFile = File(getFilesDir(), "dns_backup.txt")
            if (backupFile.exists()) {
                val psRestore = "\$map=@{}; Get-Content \"${backupFile.absolutePath.replace("\\", "/")}\" -ErrorAction SilentlyContinue | ForEach-Object { if(\$_ -match '^(\\d+)=(.*)'){ \$map[\$matches[1]]=\$matches[2] } }; Get-NetAdapter | Where-Object Status -eq 'Up' | ForEach-Object { \$idx=\"\$(\$_.InterfaceIndex)\"; if(\$map.ContainsKey(\$idx) -and \$map[\$idx]){ try{ Set-DnsClientServerAddress -InterfaceIndex \$_.InterfaceIndex -ServerAddresses @(\$map[\$idx].Split(',')) -ErrorAction SilentlyContinue }catch{} } else { try{ Set-DnsClientServerAddress -InterfaceIndex \$_.InterfaceIndex -ResetServerAddresses -ErrorAction SilentlyContinue }catch{} } }; Clear-DnsClientCache -ErrorAction SilentlyContinue; Remove-Item \"${backupFile.absolutePath.replace("\\", "/")}\" -ErrorAction SilentlyContinue"
                ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psRestore).start().waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                val psReset = "Get-NetAdapter | Where-Object Status -eq 'Up' | ForEach-Object { try { Set-DnsClientServerAddress -InterfaceIndex \$_.InterfaceIndex -ResetServerAddresses -ErrorAction SilentlyContinue } catch {} }; Clear-DnsClientCache -ErrorAction SilentlyContinue"
                ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psReset).start().waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Exception) {}
    }

    override fun isAdministrator(): Boolean = io.github.immaghzbad.aetherst.shared.core.Elevation.isElevated()
override fun relaunchAsAdmin() {
    io.github.immaghzbad.aetherst.shared.core.Elevation.relaunchElevatedAndExit()
}    override fun getInterfaceMtu(): Int = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().firstOrNull { it.isUp && !it.isLoopback && !it.isVirtual }?.mtu ?: 1500
    }.getOrDefault(1500)

    override fun isNetworkConnected(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().any {
            it.isUp && !it.isLoopback && !it.isVirtual && it.inetAddresses.asSequence().any { addr -> !addr.isLoopbackAddress }
        }
    }.getOrDefault(false)

    override fun execPing(host: String, size: Int, timeoutMs: Int, dontFragment: Boolean): Boolean = runCatching {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val pb = if (isWindows) {
            if (dontFragment) ProcessBuilder("ping", "-n", "1", "-l", size.toString(), "-f", "-w", timeoutMs.toString(), host)
            else ProcessBuilder("ping", "-n", "1", "-l", size.toString(), "-w", timeoutMs.toString(), host)
        } else {
            if (dontFragment) ProcessBuilder("ping", "-c", "1", "-s", size.toString(), "-M", "do", "-W", (timeoutMs / 1000).toString(), host)
            else ProcessBuilder("ping", "-c", "1", "-s", size.toString(), "-W", (timeoutMs / 1000).toString(), host)
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) && proc.exitValue() == 0
    }.getOrDefault(false)
}

actual fun getVpnController(context: PlatformContext): VpnController = DesktopVpnController(context)
actual fun getTrafficProvider(context: PlatformContext): TrafficProvider = DesktopTrafficProvider()
actual fun getAppInfoProvider(context: PlatformContext): AppInfoProvider = DesktopAppInfoProvider()
actual fun getSystemUtils(context: PlatformContext): SystemUtils = DesktopSystemUtils()

actual fun getCurrentTimestamp(): String {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    return formatter.format(java.util.Date())
}

actual val isDesktop: Boolean = true
actual val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true

actual fun getDeviceModel(): String = runCatching {
    val osName = System.getProperty("os.name") ?: "Unknown"
    val osArch = System.getProperty("os.arch") ?: ""
    val computerName = runCatching {
        if (osName.lowercase().contains("win")) {
            val process = ProcessBuilder("hostname").start()
            val name = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            name.ifEmpty { null }
        } else null
    }.getOrNull()
    val base = computerName ?: osName
    if (osArch.isNotEmpty()) "$base ($osArch)" else base
}.getOrDefault("Unknown PC")

actual fun getOsVersion(): String = runCatching {
    val osName = System.getProperty("os.name") ?: "Unknown"
    val osVersion = System.getProperty("os.version") ?: ""
    if (osVersion.isNotEmpty()) "$osName $osVersion" else osName
}.getOrDefault("Unknown")

@Composable
actual fun AppIcon(app: AppInfo, modifier: Modifier) {
    val iconPath = app.icon
    val bitmap = remember(iconPath) {
        if (iconPath != null && File(iconPath).exists()) {
            try {
                when {
                    iconPath.lowercase().endsWith(".lnk") -> {
                        val target = resolveLnkTarget(iconPath)
                        if (target != null && File(target).exists() && target.lowercase().endsWith(".exe")) extractExeIcon(target) else null
                    }
                    iconPath.lowercase().endsWith(".exe") -> extractExeIcon(iconPath)
                    else -> org.jetbrains.skia.Image.makeFromEncoded(File(iconPath).readBytes()).toComposeImageBitmap()
                }
            } catch (_: Exception) { null }
        } else null
    }

    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Apps, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(24.dp))
        }
    }
}

private fun resolveLnkTarget(lnkPath: String): String? = runCatching {
    val process = ProcessBuilder(
        "powershell", "-NoProfile", "-Command",
        "(New-Object -ComObject WScript.Shell).CreateShortcut('$lnkPath').TargetPath"
    ).start()
    val result = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (result.isNotEmpty() && File(result).exists()) result else null
}.getOrNull()

private fun extractExeIcon(exePath: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val safeName = "icon_" + exePath.hashCode().toString().replace("-", "N") + "_" + File(exePath).nameWithoutExtension.take(16) + ".png"
    val tempIcon = File(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"), safeName)
    val psExe = exePath.replace("'", "''")
    val tempPath = tempIcon.absolutePath.replace("'", "''")
    val process = ProcessBuilder(
        "powershell", "-NoProfile", "-Command",
        "try { [System.Drawing.Icon]::ExtractAssociatedIcon('$psExe').ToBitmap().Save('$tempPath') } catch {}"
    ).start()
    process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
    if (tempIcon.exists() && tempIcon.length() > 0) {
        val bytes = tempIcon.readBytes()
        runCatching { tempIcon.delete() }
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } else null
}.getOrNull()
