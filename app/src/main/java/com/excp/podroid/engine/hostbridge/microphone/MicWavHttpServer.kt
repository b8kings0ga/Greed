package com.excp.podroid.engine.hostbridge.microphone

import android.util.Log
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MicWavHttpServer(
    private val port: Int = DEFAULT_PORT,
    private val onClientConnected: () -> Unit = {},
    private val onLastClientDisconnected: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Client>()
    private var serverSocket: ServerSocket? = null

    val urlForGuest: String get() = "http://10.0.2.2:$port/stream.wav"
    val localUrl: String get() = "http://127.0.0.1:$port/stream.wav"
    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = clients.size

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute { acceptLoop() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
    }

    fun broadcast(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        clients.forEach { client ->
            try {
                client.out.write(pcm, 0, length)
                client.out.flush()
            } catch (_: Throwable) {
                removeClient(client)
            }
        }
    }

    private fun acceptLoop() {
        try {
            ServerSocket(port, 8, InetAddress.getByName("0.0.0.0")).use { socket ->
                serverSocket = socket
                while (running.get()) {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                }
            }
        } catch (_: SocketException) {
            // Expected when stop() closes the server socket.
        } catch (t: Throwable) {
            Log.w(TAG, "mic WAV server failed: ${t.message}")
        } finally {
            running.set(false)
            serverSocket = null
        }
    }

    private fun handleClient(socket: Socket) {
        val client = try {
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(httpHeader())
            out.write(wavHeader())
            out.flush()
            Client(socket, out)
        } catch (_: Throwable) {
            runCatching { socket.close() }
            return
        }

        clients += client
        onClientConnected()
        try {
            while (running.get() && !socket.isClosed) Thread.sleep(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            removeClient(client)
        }
    }

    private fun removeClient(client: Client) {
        val removed = clients.remove(client)
        client.close()
        if (removed && clients.isEmpty()) onLastClientDisconnected()
    }

    private fun httpHeader(): ByteArray =
        (
            "HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    private fun wavHeader(): ByteArray {
        val dataSize = Int.MAX_VALUE
        val riffSize = dataSize - 36
        return ByteArray(44).also { h ->
            h.putAscii(0, "RIFF")
            h.putLe32(4, riffSize)
            h.putAscii(8, "WAVE")
            h.putAscii(12, "fmt ")
            h.putLe32(16, 16)
            h.putLe16(20, 1)
            h.putLe16(22, CHANNELS)
            h.putLe32(24, SAMPLE_RATE)
            h.putLe32(28, SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
            h.putLe16(32, CHANNELS * BYTES_PER_SAMPLE)
            h.putLe16(34, BITS_PER_SAMPLE)
            h.putAscii(36, "data")
            h.putLe32(40, dataSize)
        }
    }

    private data class Client(
        val socket: Socket,
        val out: BufferedOutputStream,
    ) {
        fun close() {
            runCatching { out.close() }
            runCatching { socket.close() }
        }
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun ByteArray.putLe32(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value shr 8) and 0xff).toByte()
        this[offset + 2] = ((value shr 16) and 0xff).toByte()
        this[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    companion object {
        const val DEFAULT_PORT = 18082
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val TAG = "MicWavHttpServer"
    }
}
