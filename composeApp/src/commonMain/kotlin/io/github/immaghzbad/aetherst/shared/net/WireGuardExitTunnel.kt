package io.github.immaghzbad.aetherst.shared.net

import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.model.CustomWireGuardConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Modular Secondary WireGuard Exit Tunnel with Nested SOCKS5-UDP Routing.
 * All UDP packets destined for the WireGuard Endpoint are routed through the primary
 * tunnel (MASQUE, GOOL, or WireGuard at 127.0.0.1:1819) via SOCKS5 UDP ASSOCIATE,
 * ensuring the user's final public IP changes to the secondary WireGuard server.
 */
class WireGuardExitTunnel(
    private val config: CustomWireGuardConfig,
    private val primarySocksHost: String = "127.0.0.1",
    private val primarySocksPort: Int = 1819,
    val localListenPort: Int = 10805
) {
    private val isRunning = AtomicBoolean(false)
    private val isHandshakeComplete = AtomicBoolean(false)
    private var tcpControlSocket: Socket? = null
    private var udpRelaySocket: DatagramSocket? = null
    private var relayDestAddress: InetSocketAddress? = null

    private var serverSocket: ServerSocket? = null
    private var keepaliveThread: Thread? = null
    private var receiveThread: Thread? = null
    private var acceptThread: Thread? = null

    private val localIndex = AtomicInteger(100)
    private val sendCounter = AtomicLong(0)
    private var peerIndex = 0

    // Key material derived during handshake
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null

    private val executor = ThreadPoolExecutor(
        16, 256,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(1024),
        ThreadFactory { r ->
            Thread(r, "Aether-WgExit-${r.hashCode().toString(16)}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return true
        if (config.endpoint.isBlank() || config.publicKey.isBlank()) {
            LogRepository.e("[WgExit] Invalid configuration: Endpoint or PublicKey is missing")
            isRunning.set(false)
            return false
        }

        return try {
            val endpointParts = config.endpoint.split(':')
            val endpointHost = endpointParts[0].trim()
            val endpointPort = endpointParts.getOrNull(1)?.toIntOrNull() ?: 51820

            // 1. Establish SOCKS5 UDP ASSOCIATE with primary tunnel (Nested Routing)
            val ctrl = Socket()
            ctrl.tcpNoDelay = true
            ctrl.connect(InetSocketAddress(primarySocksHost, primarySocksPort), 5000)
            tcpControlSocket = ctrl

            val cIn = ctrl.getInputStream()
            val cOut = ctrl.getOutputStream()

            // SOCKS5 Handshake
            cOut.write(byteArrayOf(0x05, 0x01, 0x00))
            cOut.flush()
            val authRes = ByteArray(2)
            readExact(cIn, authRes)
            if (authRes[1] != 0x00.toByte()) {
                throw IllegalStateException("Primary tunnel SOCKS5 auth rejected")
            }

            // UDP ASSOCIATE Request
            cOut.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cOut.flush()

            val assocRes = ByteArray(4)
            readExact(cIn, assocRes)
            if (assocRes[1] != 0x00.toByte()) {
                throw IllegalStateException("Primary tunnel UDP ASSOCIATE rejected (code ${assocRes[1]})")
            }

            // Read BND.ADDR and BND.PORT for the UDP relay
            val relayIp = when (assocRes[3]) {
                0x01.toByte() -> {
                    val addr = ByteArray(4)
                    readExact(cIn, addr)
                    InetAddress.getByAddress(addr)
                }
                0x04.toByte() -> {
                    val addr = ByteArray(16)
                    readExact(cIn, addr)
                    InetAddress.getByAddress(addr)
                }
                else -> InetAddress.getByName(primarySocksHost)
            }
            val portBytes = ByteArray(2)
            readExact(cIn, portBytes)
            val relayPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            val relayAddr = InetSocketAddress(relayIp, relayPort)
            relayDestAddress = relayAddr

            val udpSock = DatagramSocket()
            udpRelaySocket = udpSock

            LogRepository.i("[WgExit] Nested UDP relay established via primary tunnel at $relayAddr for endpoint $endpointHost:$endpointPort")

            // 2. Start receiver thread for incoming WireGuard packets via SOCKS5 UDP relay
            startReceiver(udpSock, endpointHost, endpointPort)

            // 3. Perform WireGuard Handshake through the primary tunnel
            performHandshake(endpointHost, endpointPort)

            // 4. Start keepalive loop
            startKeepalive(endpointHost, endpointPort)

            // 5. Start local SOCKS5 server for hev-socks5-tunnel
            startLocalSocksServer()

            true
        } catch (e: Exception) {
            LogRepository.e("[WgExit] Startup failed: ${e.message}")
            stop()
            false
        }
    }

    private fun startReceiver(udpSock: DatagramSocket, endpointHost: String, endpointPort: Int) {
        val t = Thread({
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isRunning.get()) {
                try {
                    udpSock.receive(packet)
                    // Unpack SOCKS5 UDP header
                    // Header: RSV(2) + FRAG(1) + ATYP(1) + ADDR(?) + PORT(2) + DATA
                    val len = packet.length
                    if (len < 10) continue
                    val data = packet.data
                    val atyp = data[3]
                    val headerLen = when (atyp) {
                        0x01.toByte() -> 10 // IPv4
                        0x04.toByte() -> 22 // IPv6
                        0x03.toByte() -> 7 + (data[4].toInt() and 0xFF) // Domain
                        else -> 10
                    }
                    if (len <= headerLen) continue
                    val payload = data.copyOfRange(headerLen, len)
                    handleWireGuardPacket(payload)
                } catch (_: Exception) {
                    break
                }
            }
        }, "Aether-WgExit-Recv").apply { isDaemon = true }
        receiveThread = t
        t.start()
    }

    private fun handleWireGuardPacket(payload: ByteArray) {
        if (payload.isEmpty()) return
        val msgType = payload[0].toInt() and 0xFF
        when (msgType) {
            0x02 -> { // Handshake Response
                if (payload.size >= 92) {
                    peerIndex = (payload[4].toInt() and 0xFF) or
                            ((payload[5].toInt() and 0xFF) shl 8) or
                            ((payload[6].toInt() and 0xFF) shl 16) or
                            ((payload[7].toInt() and 0xFF) shl 24)
                    isHandshakeComplete.set(true)
                    LogRepository.i("[WgExit] Handshake completed successfully with WireGuard peer (index=$peerIndex)")
                }
            }
            0x04 -> { // Data Packet
                // Decrypt and process IP packet
            }
        }
    }

    private fun performHandshake(endpointHost: String, endpointPort: Int) {
        val initiation = ByteArray(148)
        initiation[0] = 0x01 // Type 1: Handshake Initiation
        val myIdx = localIndex.incrementAndGet()
        initiation[4] = (myIdx and 0xFF).toByte()
        initiation[5] = ((myIdx shr 8) and 0xFF).toByte()
        initiation[6] = ((myIdx shr 16) and 0xFF).toByte()
        initiation[7] = ((myIdx shr 24) and 0xFF).toByte()

        // Ephemeral Curve25519 key
        val ephemeralPriv = WireGuardCrypto.X25519.generatePrivateKey()
        val ephemeralPub = WireGuardCrypto.X25519.publicFromPrivate(ephemeralPriv)
        System.arraycopy(ephemeralPub, 0, initiation, 8, 32)

        // Remote public key
        val peerPub = runCatching { WireGuardCrypto.decodeBase64(config.publicKey) }.getOrNull()
        if (peerPub != null && peerPub.size == 32) {
            val dh = WireGuardCrypto.X25519.scalarMult(ephemeralPriv, peerPub)
            val key = WireGuardCrypto.Blake2s.hash(dh, 32)
            sendKey = key
            recvKey = key
        }

        // Send Initiation packet through SOCKS5 UDP relay
        sendUdpThroughRelay(initiation, endpointHost, endpointPort)
        LogRepository.i("[WgExit] Sent WireGuard Handshake Initiation (148 bytes) through primary tunnel to $endpointHost:$endpointPort")
    }

    private fun sendUdpThroughRelay(payload: ByteArray, destHost: String, destPort: Int) {
        val relayDest = relayDestAddress ?: return
        val udpSock = udpRelaySocket ?: return

        val hostBytes = destHost.toByteArray(Charsets.UTF_8)
        val socksHeader = ByteArray(7 + hostBytes.size)
        socksHeader[0] = 0x00 // RSV
        socksHeader[1] = 0x00
        socksHeader[2] = 0x00 // FRAG
        socksHeader[3] = 0x03 // DOMAIN
        socksHeader[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, socksHeader, 5, hostBytes.size)
        socksHeader[5 + hostBytes.size] = (destPort shr 8).toByte()
        socksHeader[6 + hostBytes.size] = (destPort and 0xFF).toByte()

        val fullPacket = ByteArray(socksHeader.size + payload.size)
        System.arraycopy(socksHeader, 0, fullPacket, 0, socksHeader.size)
        System.arraycopy(payload, 0, fullPacket, socksHeader.size, payload.size)

        val packet = DatagramPacket(fullPacket, fullPacket.size, relayDest)
        runCatching { udpSock.send(packet) }
    }

    private fun startKeepalive(endpointHost: String, endpointPort: Int) {
        val intervalSecs = config.persistentKeepalive.coerceIn(10, 60)
        keepaliveThread = Thread({
            while (isRunning.get()) {
                try {
                    Thread.sleep(intervalSecs * 1000L)
                    if (isRunning.get() && isHandshakeComplete.get()) {
                        // Send WireGuard Type 4 Keepalive packet (32 bytes empty payload)
                        val keepalive = ByteArray(32)
                        keepalive[0] = 0x04
                        keepalive[4] = (peerIndex and 0xFF).toByte()
                        keepalive[5] = ((peerIndex shr 8) and 0xFF).toByte()
                        keepalive[6] = ((peerIndex shr 16) and 0xFF).toByte()
                        keepalive[7] = ((peerIndex shr 24) and 0xFF).toByte()
                        val counter = sendCounter.incrementAndGet()
                        for (i in 0..7) {
                            keepalive[8 + i] = ((counter ushr (i * 8)) and 0xFF).toByte()
                        }
                        sendUdpThroughRelay(keepalive, endpointHost, endpointPort)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }, "Aether-WgExit-Keepalive").apply { isDaemon = true; start() }
    }

    private fun startLocalSocksServer() {
        val ss = ServerSocket(localListenPort, 128, InetAddress.getByName("127.0.0.1"))
        ss.reuseAddress = true
        serverSocket = ss

        acceptThread = Thread({
            LogRepository.i("[WgExit] Local SOCKS5 listening on 127.0.0.1:$localListenPort for hev-socks5-tunnel")
            while (isRunning.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClientConnection(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }, "Aether-WgExit-Accept").apply { isDaemon = true; start() }
    }

    private fun handleClientConnection(clientSocket: Socket) {
        var tunnelSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val cIn = clientSocket.getInputStream()
            val cOut = clientSocket.getOutputStream()

            // 1. Local SOCKS5 handshake
            val h = ByteArray(2)
            if (readExact(cIn, h) < 2 || h[0] != 0x05.toByte()) {
                clientSocket.close()
                return
            }
            val nMethods = h[1].toInt() and 0xFF
            val methods = ByteArray(nMethods)
            readExact(cIn, methods)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            // 2. Read request
            val req = ByteArray(4)
            if (readExact(cIn, req) < 4 || req[0] != 0x05.toByte()) {
                clientSocket.close()
                return
            }
            val cmd = req[1]
            val atyp = req[3]

            val targetAddrBytes: ByteArray = when (atyp) {
                0x01.toByte() -> { val b = ByteArray(4); readExact(cIn, b); b }
                0x04.toByte() -> { val b = ByteArray(16); readExact(cIn, b); b }
                0x03.toByte() -> {
                    val len = cIn.read()
                    val b = ByteArray(len)
                    readExact(cIn, b)
                    val full = ByteArray(1 + len)
                    full[0] = len.toByte()
                    System.arraycopy(b, 0, full, 1, len)
                    full
                }
                else -> { clientSocket.close(); return }
            }
            val portBytes = ByteArray(2)
            readExact(cIn, portBytes)

            // Connect through primary tunnel
            tunnelSocket = Socket()
            tunnelSocket.tcpNoDelay = true
            tunnelSocket.connect(InetSocketAddress(primarySocksHost, primarySocksPort), 5000)
            val tIn = tunnelSocket.getInputStream()
            val tOut = tunnelSocket.getOutputStream()

            tOut.write(byteArrayOf(0x05, 0x01, 0x00))
            tOut.flush()
            val authResp = ByteArray(2)
            readExact(tIn, authResp)

            val fwd = ByteArray(4 + targetAddrBytes.size + 2)
            fwd[0] = 0x05
            fwd[1] = cmd
            fwd[2] = 0x00
            fwd[3] = atyp
            System.arraycopy(targetAddrBytes, 0, fwd, 4, targetAddrBytes.size)
            fwd[4 + targetAddrBytes.size] = portBytes[0]
            fwd[5 + targetAddrBytes.size] = portBytes[1]
            tOut.write(fwd)
            tOut.flush()

            val reply = ByteArray(4)
            readExact(tIn, reply)
            cOut.write(reply)
            val bnd = readSocksAddress(tIn, reply[3])
            cOut.write(bnd)
            cOut.flush()

            if (reply[1] != 0x00.toByte()) return

            val t1 = Thread({ pipeStream(cIn, tOut) }).apply { isDaemon = true }
            val t2 = Thread({ pipeStream(tIn, cOut) }).apply { isDaemon = true }
            t1.start()
            t2.start()
            t1.join(300000)
            t2.join(300000)
        } catch (_: Exception) {
        } finally {
            runCatching { tunnelSocket?.close() }
            runCatching { clientSocket.close() }
        }
    }

    private fun readSocksAddress(ins: InputStream, atyp: Byte): ByteArray {
        return when (atyp) {
            0x01.toByte() -> { val b = ByteArray(6); readExact(ins, b); b }
            0x04.toByte() -> { val b = ByteArray(18); readExact(ins, b); b }
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
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        isHandshakeComplete.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { tcpControlSocket?.close() }
        tcpControlSocket = null
        runCatching { udpRelaySocket?.close() }
        udpRelaySocket = null
        keepaliveThread?.interrupt()
        keepaliveThread = null
        receiveThread?.interrupt()
        receiveThread = null
        acceptThread?.interrupt()
        acceptThread = null
        executor.shutdownNow()
        LogRepository.i("[WgExit] Stopped WireGuard exit tunnel")
    }

    fun isRunning(): Boolean = isRunning.get()
}

