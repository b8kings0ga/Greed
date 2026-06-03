package com.excp.podroid.engine.hostbridge.microphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.hostbridge.HostProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

@Singleton
class MicrophoneStreamManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val httpServer = MicWavHttpServer(
        onClientConnected = { onStreamClientConnected() },
        onLastClientDisconnected = { onLastStreamClientDisconnected() },
    )

    @Volatile private var lastError: String? = null
    @Volatile private var lastLevel: Int = 0
    @Volatile private var bytesCaptured: Long = 0

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var running = false
    private var lazyOnAccess = true
    private var idleStopToken = 0

    fun start(): String = synchronized(lock) {
        httpServer.start()
        if (!hasMicrophonePermission()) {
            lastError = "microphone permission not granted"
            return HostProtocol.err(lastError!!)
        }
        if (running) return HostProtocol.ok(HostProtocol.enc(statusJson()))
        lastError = null
        running = true
        openMicrophoneLocked()
        HostProtocol.ok(HostProtocol.enc(statusJson()))
    }

    fun stop(): String = synchronized(lock) {
        stopLocked()
        HostProtocol.ok()
    }

    fun ensureServerStarted() {
        httpServer.start()
    }

    fun status(): String = synchronized(lock) {
        HostProtocol.ok(HostProtocol.enc(statusJson()))
    }

    fun url(): String = synchronized(lock) {
        httpServer.start()
        HostProtocol.ok(HostProtocol.enc(httpServer.urlForGuest))
    }

    fun lazy(action: String): String = synchronized(lock) {
        when (action) {
            "on" -> {
                lazyOnAccess = true
                if (httpServer.clientCount == 0) scheduleIdleStopLocked(0)
            }
            "off" -> {
                lazyOnAccess = false
                start()
            }
            "status" -> {}
            else -> return HostProtocol.err("usage: lazy on|off|status")
        }
        HostProtocol.ok(HostProtocol.enc(statusJson()))
    }

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openMicrophoneLocked() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            lastError = "unsupported microphone format"
            stopLocked()
            return
        }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE)
        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
            )
        }.onFailure {
            lastError = it.message ?: it.javaClass.simpleName
            stopLocked()
        }.getOrNull() ?: return

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            lastError = "microphone initialization failed"
            stopLocked()
            return
        }

        recorder = audioRecord
        captureThread = thread(name = "PodroidMicrophoneStream") {
            captureLoop(audioRecord, bufferSize)
        }
    }

    private fun captureLoop(audioRecord: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            audioRecord.startRecording()
            while (running && !Thread.currentThread().isInterrupted) {
                val n = audioRecord.read(buffer, 0, buffer.size)
                if (n > 0) {
                    bytesCaptured += n.toLong()
                    lastLevel = peakLevel(buffer, n)
                    httpServer.broadcast(buffer, n)
                } else if (n < 0) {
                    lastError = "audio read error $n"
                    break
                }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            synchronized(lock) {
                if (recorder === audioRecord) recorder = null
                if (captureThread === Thread.currentThread()) captureThread = null
                running = false
            }
        }
    }

    private fun onStreamClientConnected() {
        synchronized(lock) {
            if (!lazyOnAccess || running) return
            if (!hasMicrophonePermission()) {
                lastError = "microphone permission not granted"
                return
            }
            idleStopToken++
            lastError = null
            running = true
            openMicrophoneLocked()
        }
    }

    private fun onLastStreamClientDisconnected() {
        synchronized(lock) {
            if (lazyOnAccess) scheduleIdleStopLocked(LAZY_IDLE_STOP_MS)
        }
    }

    private fun scheduleIdleStopLocked(delayMs: Long) {
        val token = ++idleStopToken
        Thread {
            if (delayMs > 0) SystemClock.sleep(delayMs)
            synchronized(lock) {
                if (token == idleStopToken && lazyOnAccess && httpServer.clientCount == 0) {
                    stopMicrophoneLocked()
                }
            }
        }.start()
    }

    private fun stopLocked() {
        idleStopToken++
        stopMicrophoneLocked()
        httpServer.stop()
    }

    private fun stopMicrophoneLocked() {
        running = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        captureThread?.interrupt()
        captureThread = null
        lastLevel = 0
    }

    private fun statusJson(): String {
        val error = lastError
        return buildString {
            append('{')
            append("\"running\":").append(running)
            append(",\"serverRunning\":").append(httpServer.isRunning)
            append(",\"lazyOnAccess\":").append(lazyOnAccess)
            append(",\"clients\":").append(httpServer.clientCount)
            append(",\"permission\":").append(hasMicrophonePermission())
            append(",\"url\":\"").append(httpServer.urlForGuest).append('"')
            append(",\"localUrl\":\"").append(httpServer.localUrl).append('"')
            append(",\"sampleRate\":").append(SAMPLE_RATE)
            append(",\"channels\":1")
            append(",\"bitsPerSample\":16")
            append(",\"bytesCaptured\":").append(bytesCaptured)
            append(",\"level\":").append(lastLevel)
            if (error != null) append(",\"error\":\"").append(jsonEscape(error)).append('"')
            append('}')
        }
    }

    private fun peakLevel(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
            val value = if (sample < 0) -sample else sample
            if (value > peak) peak = value
            i += 2
        }
        return peak
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val SAMPLE_RATE = MicWavHttpServer.SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val LAZY_IDLE_STOP_MS = 10_000L
    }
}
