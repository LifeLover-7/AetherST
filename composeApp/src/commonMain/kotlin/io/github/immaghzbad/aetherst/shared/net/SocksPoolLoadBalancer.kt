package io.github.immaghzbad.aetherst.shared.net

import io.github.immaghzbad.aetherst.shared.data.LogRepository
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, low-latency Round-Robin SOCKS5 Load Balancer.
 * Distributes concurrent TCP connections from hev-socks5-tunnel across a pool of
 * local Psiphon instances (e.g. 10801..10804) to bypass single-connection bandwidth limits.
 */
class SocksPoolLoadBalancer(
    private val listenHost: String = "127.0.0.1",
    val listenPort: Int = 10800,
    private val upstreamPorts: List<Int> = listOf(10801, 10802, 10803, 10804)
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val counter = AtomicInteger(0)
    private var acceptThread: Thread? = null

    private val executor = ThreadPoolExecutor(
        16, 256,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(1024),
        ThreadFactory { r ->
            Thread(r, "Aether-SocksLB-${r.hashCode().toString(16)}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return true
        return try {
            val s = ServerSocket(listenPort, 128, InetAddress.getByName(listenHost))
            s.reuseAddress = true
            serverSocket = s

            acceptThread = Thread({
                LogRepository.i("[SocksLB] Pool load balancer started on $listenHost:$listenPort -> [${upstreamPorts.joinToString()}]")
                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleConnection(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "Aether-SocksLB-Accept").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            isRunning.set(false)
            LogRepository.e("[SocksLB] Failed to bind on $listenHost:$listenPort: ${e.message}")
            false
        }
    }

    private fun handleConnection(clientSocket: Socket) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true

            if (upstreamPorts.isEmpty()) {
                clientSocket.close()
                return
            }

            val startIndex = (counter.getAndIncrement() and 0x7FFFFFFF) % upstreamPorts.size
            var connected = false

            // Round-robin selection with instant failover across pool
            for (i in upstreamPorts.indices) {
                val targetPort = upstreamPorts[(startIndex + i) % upstreamPorts.size]
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress("127.0.0.1", targetPort), 1500)
                    upstreamSocket = s
                    connected = true
                    break
                } catch (_: Exception) {
                    // Try next healthy instance in the pool
                }
            }

            if (!connected || upstreamSocket == null) {
                clientSocket.close()
                return
            }

            val cIn = clientSocket.getInputStream()
            val cOut = clientSocket.getOutputStream()
            val uIn = upstreamSocket.getInputStream()
            val uOut = upstreamSocket.getOutputStream()

            val t1 = Thread({ pipeStream(cIn, uOut) }, "Aether-LB-C2U").apply { isDaemon = true }
            val t2 = Thread({ pipeStream(uIn, cOut) }, "Aether-LB-U2C").apply { isDaemon = true }
            t1.start()
            t2.start()

            t1.join(300000)
            t2.join(300000)
        } catch (_: Exception) {
        } finally {
            runCatching { upstreamSocket?.close() }
            runCatching { clientSocket.close() }
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32768)
        try {
            while (isRunning.get()) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        executor.shutdownNow()
        LogRepository.i("[SocksLB] Pool load balancer stopped")
    }

    fun isRunning(): Boolean = isRunning.get()
}

