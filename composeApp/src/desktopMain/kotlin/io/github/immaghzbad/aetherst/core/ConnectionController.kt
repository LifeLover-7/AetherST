package io.github.immaghzbad.aetherst.shared.core

import io.github.immaghzbad.aetherst.shared.data.AetherConfigRepository
import io.github.immaghzbad.aetherst.shared.data.ActiveProxyProvider
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

        private var connectJob: Job? = null
        private var timerJob: Job? = null
        private var baseTx = 0L
        private var baseRx = 0L
        private var prevTx = 0L
        private var prevRx = 0L

        @Volatile private var tunnelModeStarted = false
        @Volatile privat
