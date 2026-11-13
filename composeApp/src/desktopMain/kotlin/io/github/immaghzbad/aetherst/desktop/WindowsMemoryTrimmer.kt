package io.github.immaghzbad.aetherst.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.immaghzbad.aetherst.shared.desktop.DesktopLogger
import kotlinx.coroutines.*

interface Kernel32Lib : Library {
    fun SetProcessWorkingSetSize(hProcess: Pointer, dwMinimumWorkingSetSize: Long, dwMaximumWorkingSetSize: Long): Boolean
    fun GetCurrentProcess(): Pointer
}

object WindowsMemoryTrimmer {

    private val kernel32: Kernel32Lib? by lazy {
        try {
            val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
            if (isWin) {
                Native.load("kernel32", Kernel32Lib::class.java)
            } else null
        } catch (e: Throwable) {
            DesktopLogger.w("MemoryTrimmer", "Kernel32 load failed: ${e.message}")
            null
        }
    }

    private val trimmerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var isStarted = false

    fun trim() {
        try {
            System.gc()
            val k32 = kernel32 ?: return
            val proc = k32.GetCurrentProcess()
            k32.SetProcessWorkingSetSize(proc, -1L, -1L)
        } catch (_: Throwable) {}
    }

    fun startPeriodicTrimmer() {
        if (isStarted) return
        isStarted = true
        trimmerScope.launch {
            // First trim 3 seconds after boot (after UI layout settles)
            delay(3000)
            trim()

            // Continuous background trim every 15 seconds
            while (isActive) {
                delay(15000)
                trim()
            }
        }
    }
}

