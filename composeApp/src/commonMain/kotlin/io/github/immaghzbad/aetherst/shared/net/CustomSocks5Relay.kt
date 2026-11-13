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

/**
 * Modular Chained SOCKS5 Relay.
 * Bridges hev-socks5-tunnel through the primary tunnel (127.0.0.1:1819) to any
 * custom external SOCKS5 proxy server without adding any third-party dependencies.
 */
class CustomSocks5Relay(
    private val listenHost: String = "127.0.0.1",
    val listenPort: Int = 10806,
    private val primarySocksHost: String = "127.0.0.1",
    private val primarySocksPort: Int = 1819,
    private val remoteHost: String,
    private val remotePort: Int,
    private val username: String = "",
    private val password: String = ""
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val executor = ThreadPoolExecutor(
        16, 256,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(1024),
        ThreadFactory { r ->
            Thread(r, "Aether-SocksRelay-${r.hashCode().toString(16)}").apply { isDaemon = true }
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
                LogRepository.i("[SocksRelay] Started on $listenHost:$listenPort -> primary $primarySocksHost:$primarySocksPort -> remote $remoteHost:$remotePort")
                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleRelay(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "Aether-SocksRelay-Accept").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            isRunning.set(false)
            LogRepository.e("[SocksRelay] Failed to bind $listenHost:$listenPort: ${e.message}")
            false
        }
    }

    private fun handleRelay(clientSocket: Socket) {
        var primarySocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. SOCKS5 handshake with client (hev-socks5-tunnel)
            val header = ByteArray(2)
            if (readExact(clientIn, header) < 2 || header[0] != 0x05.toByte()) {
                clientSocket.close()
                return
            }
            val nMethods = header[1].toInt() and 0xFF
            val methods = ByteArray(nMethods)
            readExact(clientIn, methods)
            clientOut.write(byteArrayOf(0x05, 0x00)) // NO AUTH
            clientOut.flush()

            // 2. Read client request
            val requestHeader = ByteArray(4)
            if (readExact(clientIn, requestHeader) < 4 || requestHeader[0] != 0x05.toByte()) {
                clientSocket.close()
                return
            }
            val cmd = requestHeader[1]
            val atyp = requestHeader[3]

            val targetAddrBytes: ByteArray = when (atyp) {
                0x01.toByte() -> { // IPv4
                    val b = ByteArray(4)
                    readExact(clientIn, b)
                    b
                }
                0x04.toByte() -> { // IPv6
                    val b = ByteArray(16)
                    readExact(clientIn, b)
                    b
                }
                0x03.toByte() -> { // Domain
                    val len = clientIn.read()
                    val b = ByteArray(len)
                    readExact(clientIn, b)
                    val full = ByteArray(1 + len)
                    full[0] = len.toByte()
                    System.arraycopy(b, 0, full, 1, len)
                    full
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }
            val portBytes = ByteArray(2)
            readExact(clientIn, portBytes)

            // 3. Connect to primary tunnel SOCKS5 (127.0.0.1:1819)
            primarySocket = Socket()
            primarySocket.tcpNoDelay = true
            primarySocket.connect(InetSocketAddress(primarySocksHost, primarySocksPort), 5000)
            val primIn = primarySocket.getInputStream()
            val primOut = primarySocket.getOutputStream()

            // Primary tunnel handshake
            primOut.write(byteArrayOf(0x05, 0x01, 0x00))
            primOut.flush()
            val primAuth = ByteArray(2)
            readExact(primIn, primAuth)

            // Primary tunnel CONNECT to remote custom SOCKS5 host:port
            val remoteHostBytes = remoteHost.toByteArray(Charsets.UTF_8)
            val connectReq = ByteArray(7 + remoteHostBytes.size)
            connectReq[0] = 0x05
            connectReq[1] = 0x01 // CONNECT
            connectReq[2] = 0x00
            connectReq[3] = 0x03 // Domain
            connectReq[4] = remoteHostBytes.size.toByte()
            System.arraycopy(remoteHostBytes, 0, connectReq, 5, remoteHostBytes.size)
            connectReq[5 + remoteHostBytes.size] = (remotePort shr 8).toByte()
            connectReq[6 + remoteHostBytes.size] = (remotePort and 0xFF).toByte()
            primOut.write(connectReq)
            primOut.flush()

            val primReply = ByteArray(4)
            readExact(primIn, primReply)
            if (primReply[1] != 0x00.toByte()) {
                clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                return
            }
            // Discard bind address from primary response
            discardSocksAddress(primIn, primReply[3])

            // 4. Now authenticated through primary tunnel, handshake with remote custom SOCKS5 server!
            if (username.isNotEmpty()) {
                primOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) // NO AUTH or USER/PASS
                primOut.flush()
                val remAuthMethod = ByteArray(2)
                readExact(primIn, remAuthMethod)
                if (remAuthMethod[1] == 0x02.toByte()) {
                    // Send username/password (RFC 1929)
                    val uBytes = username.toByteArray(Charsets.UTF_8)
                    val pBytes = password.toByteArray(Charsets.UTF_8)
                    val authPayload = ByteArray(3 + uBytes.size + pBytes.size)
                    authPayload[0] = 0x01
                    authPayload[1] = uBytes.size.toByte()
                    System.arraycopy(uBytes, 0, authPayload, 2, uBytes.size)
                    authPayload[2 + uBytes.size] = pBytes.size.toByte()
                    System.arraycopy(pBytes, 0, authPayload, 3 + uBytes.size, pBytes.size)
                    primOut.write(authPayload)
                    primOut.flush()
                    val authRes = ByteArray(2)
                    readExact(primIn, authRes)
                    if (authRes[1] != 0x00.toByte()) {
                        clientOut.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        clientOut.flush()
                        return
                    }
                }
            } else {
                primOut.write(byteArrayOf(0x05, 0x01, 0x00))
                primOut.flush()
                val remAuthMethod = ByteArray(2)
                readExact(primIn, remAuthMethod)
            }

            // 5. Send client's original target request to the remote SOCKS5 server
            val fwdRequest = ByteArray(4 + targetAddrBytes.size + 2)
            fwdRequest[0] = 0x05
            fwdRequest[1] = cmd
            fwdRequest[2] = 0x00
            fwdRequest[3] = atyp
            System.arraycopy(targetAddrBytes, 0, fwdRequest, 4, targetAddrBytes.size)
            fwdRequest[4 + targetAddrBytes.size] = portBytes[0]
            fwdRequest[5 + targetAddrBytes.size] = portBytes[1]
            primOut.write(fwdRequest)
            primOut.flush()

            // 6. Forward remote server's response back to client
            val remReply = ByteArray(4)
            readExact(primIn, remReply)
            clientOut.write(remReply)
            val bndAddr = readSocksAddress(primIn, remReply[3])
            clientOut.write(bndAddr)
            clientOut.flush()

            if (remReply[1] != 0x00.toByte()) return

            // 7. Bidirectional data pipe
            val t1 = Thread({ pipeStream(clientIn, primOut) }, "Aether-SocksRelay-C2P").apply { isDaemon = true }
            val t2 = Thread({ pipeStream(primIn, clientOut) }, "Aether-SocksRelay-P2C").apply { isDaemon = true }
            t1.start()
            t2.start()

            t1.join(300000)
            t2.join(300000)
        } catch (_: Exception) {
        } finally {
            runCatching { primarySocket?.close() }
            runCatching { clientSocket.close() }
        }
    }

    private fun discardSocksAddress(ins: InputStream, atyp: Byte) {
        when (atyp) {
            0x01.toByte() -> readExact(ins, ByteArray(6))
            0x04.toByte() -> readExact(ins, ByteArray(18))
            0x03.toByte() -> {
                val len = ins.read()
                readExact(ins, ByteArray(len + 2))
            }
        }
    }

    private fun readSocksAddress(ins: InputStream, atyp: Byte): ByteArray {
        return when (atyp) {
            0x01.toByte() -> {
                val b = ByteArray(6)
                readExact(ins, b)
                b
            }
            0x04.toByte() -> {
                val b = ByteArray(18)
                readExact(ins, b)
                b
            }
            0x03.toByte() -> {
                val len = ins.read()
                val b = ByteArray(len + 2)
                readExact(ins, b)
                val full = ByteArray(1 + b.size)
                full[0] = len.toByte()
                System.arraycopy(b, 0, full, 1, b.size)
                full
            }
            else -> ByteArray(0)
        }
    }

    private fun readExact(ins: InputStream, b: ByteArray): Int {
        var o = 0
        while (o < b.size) {
            val c = ins.read(b, o, b.size - o)
            if (c < 0) return o
            o += c
        }
        return o
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
        LogRepository.i("[SocksRelay] Stopped")
    }

    fun isRunning(): Boolean = isRunning.get()
}

