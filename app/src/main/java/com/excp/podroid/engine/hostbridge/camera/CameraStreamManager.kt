package com.excp.podroid.engine.hostbridge.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.hostbridge.HostProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraStreamManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val httpServer = MjpegHttpServer(frameProvider = { latestJpeg })
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var lastError: String? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var running = false
    private var selectedCameraId: String? = null

    fun start(): String = synchronized(lock) {
        if (!hasCameraPermission()) {
            lastError = "camera permission not granted"
            return HostProtocol.err(lastError!!)
        }
        if (running) return HostProtocol.ok(HostProtocol.enc(statusJson()))
        lastError = null
        running = true
        httpServer.start()
        openCameraLocked()
        HostProtocol.ok(HostProtocol.enc(statusJson()))
    }

    fun stop(): String = synchronized(lock) {
        stopLocked()
        HostProtocol.ok()
    }

    fun status(): String = synchronized(lock) {
        HostProtocol.ok(HostProtocol.enc(statusJson()))
    }

    fun list(): String = synchronized(lock) {
        HostProtocol.ok(HostProtocol.enc(cameraListJson()))
    }

    fun select(cameraId: String): String = synchronized(lock) {
        val manager = context.getSystemService(CameraManager::class.java)
        if (cameraId !in manager.cameraIdList) return HostProtocol.err("unknown camera id")
        selectedCameraId = cameraId
        if (running) {
            stopLocked()
            running = true
            httpServer.start()
            openCameraLocked()
        }
        HostProtocol.ok(HostProtocol.enc(statusJson()))
    }

    fun url(): String = synchronized(lock) {
        if (!running) return HostProtocol.err("camera stream not running")
        HostProtocol.ok(HostProtocol.enc(httpServer.urlForGuest))
    }

    fun ensureStartedIfPermitted() {
        if (hasCameraPermission()) start()
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openCameraLocked() {
        val thread = HandlerThread("PodroidCameraStream").also { it.start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler

        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                latestJpeg = runCatching { imageToJpeg(image) }
                    .onFailure { lastError = it.message ?: it.javaClass.simpleName }
                    .getOrNull()
            }
        }, handler)

        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = selectedCameraId?.takeIf { it in manager.cameraIdList } ?: selectBackCamera(manager)
        if (cameraId == null) {
            lastError = "no back camera"
            stopLocked()
            return
        }
        selectedCameraId = cameraId

        runCatching {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    synchronized(lock) {
                        cameraDevice = camera
                        createSessionLocked(camera, reader)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    synchronized(lock) {
                        lastError = "camera disconnected"
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    synchronized(lock) {
                        lastError = "camera error $error"
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                    }
                }
            }, handler)
        }.onFailure {
            lastError = it.message ?: it.javaClass.simpleName
            stopLocked()
        }
    }

    private fun createSessionLocked(camera: CameraDevice, reader: ImageReader) {
        val handler = cameraHandler ?: return
        runCatching {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(lock) {
                            captureSession = session
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            session.setRepeatingRequest(request, null, handler)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        synchronized(lock) { lastError = "camera session configure failed" }
                    }
                },
                handler,
            )
        }.onFailure { lastError = it.message ?: it.javaClass.simpleName }
    }

    private fun stopLocked() {
        running = false
        httpServer.stop()
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        captureSession = null
        cameraDevice = null
        imageReader = null
        latestJpeg = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun statusJson(): String {
        val error = lastError
        val frameReady = latestJpeg != null
        return buildString {
            append('{')
            append("\"running\":").append(running)
            append(",\"permission\":").append(hasCameraPermission())
            append(",\"frameReady\":").append(frameReady)
            append(",\"url\":\"").append(httpServer.urlForGuest).append('"')
            append(",\"localUrl\":\"").append(httpServer.localUrl).append('"')
            append(",\"cameraId\":\"").append(jsonEscape(selectedCameraId ?: "")).append('"')
            append(",\"width\":").append(WIDTH)
            append(",\"height\":").append(HEIGHT)
            if (error != null) append(",\"error\":\"").append(jsonEscape(error)).append('"')
            append('}')
        }
    }

    private fun selectBackCamera(manager: CameraManager): String? =
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()

    private fun cameraListJson(): String {
        val manager = context.getSystemService(CameraManager::class.java)
        return manager.cameraIdList.joinToString(prefix = "[", postfix = "]") { id ->
            val chars = manager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "unknown"
            }
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString(prefix = "[", postfix = "]") { trimFloat(it) } ?: "[]"
            val physicalIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                chars.physicalCameraIds.joinToString(prefix = "[", postfix = "]") { "\"${jsonEscape(it)}\"" }
            } else {
                "[]"
            }
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.previewSizesJson() ?: "[]"
            buildString {
                append('{')
                append("\"id\":\"").append(jsonEscape(id)).append('"')
                append(",\"facing\":\"").append(facing).append('"')
                append(",\"selected\":").append(id == selectedCameraId)
                append(",\"focalLengths\":").append(focalLengths)
                append(",\"physicalCameraIds\":").append(physicalIds)
                append(",\"previewSizes\":").append(sizes)
                append('}')
            }
        }
    }

    private fun StreamConfigurationMap.previewSizesJson(): String =
        getOutputSizes(ImageFormat.YUV_420_888)
            ?.sortedWith(compareBy<Size> { it.width * it.height }.thenBy { it.width })
            ?.takeLast(8)
            ?.joinToString(prefix = "[", postfix = "]") { "{\"width\":${it.width},\"height\":${it.height}}" }
            ?: "[]"

    private fun trimFloat(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun imageToJpeg(image: Image): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        return ByteArrayOutputStream().use { out ->
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        copyPlane(image.planes[0], width, height, out, 0, 1)
        copyPlane(image.planes[2], width / 2, height / 2, out, ySize, 2)
        copyPlane(image.planes[1], width / 2, height / 2, out, ySize + 1, 2)
        return out
    }

    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        pixelStrideOut: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outIndex = offset
        val row = ByteArray(rowStride)
        for (rowIndex in 0 until height) {
            val rowLength = if (rowIndex == height - 1) {
                buffer.remaining().coerceAtMost(rowStride)
            } else {
                rowStride
            }
            buffer.get(row, 0, rowLength)
            var col = 0
            while (col < width) {
                output[outIndex] = row[col * pixelStride]
                outIndex += pixelStrideOut
                col++
            }
        }
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val WIDTH = 640
        private const val HEIGHT = 480
        private const val JPEG_QUALITY = 70
    }
}
