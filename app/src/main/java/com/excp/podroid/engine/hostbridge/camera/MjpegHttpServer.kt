package com.excp.podroid.engine.hostbridge.camera

import android.util.Log
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MjpegHttpServer(
    private val port: Int = DEFAULT_PORT,
    private val frameProvider: () -> ByteArray?,
    private val onClientConnected: () -> Unit = {},
    private val onLastClientDisconnected: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Socket>()
    private var serverSocket: ServerSocket? = null

    val urlForGuest: String get() = "http://10.0.2.2:$port/stream.mjpg"
    val localUrl: String get() = "http://127.0.0.1:$port/stream.mjpg"
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
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop() {
        try {
            ServerSocket(port, 8, InetAddress.getByName("0.0.0.0")).use { socket ->
                serverSocket = socket
                while (running.get()) {
                    val client = socket.accept()
                    clients += client
                    executor.execute { handleClient(client) }
                }
            }
        } catch (_: SocketException) {
            // Expected when stop() closes the server socket.
        } catch (t: Throwable) {
            Log.w(TAG, "MJPEG server failed: ${t.message}")
        } finally {
            running.set(false)
            serverSocket = null
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            onClientConnected()
            BufferedOutputStream(socket.getOutputStream()).use { out ->
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Connection: close\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                while (running.get() && !socket.isClosed) {
                    val frame = frameProvider()
                    if (frame == null) {
                        Thread.sleep(100)
                        continue
                    }
                    out.write(
                        (
                            "--$BOUNDARY\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                    )
                    out.write(frame)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    Thread.sleep(FRAME_DELAY_MS)
                }
            }
        } catch (_: Throwable) {
            // Clients are expected to disconnect freely.
        } finally {
            clients -= socket
            runCatching { socket.close() }
            if (clients.isEmpty()) onLastClientDisconnected()
        }
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val TAG = "MjpegHttpServer"
        private const val BOUNDARY = "podroid-mjpeg"
        private const val FRAME_DELAY_MS = 100L
    }
}
