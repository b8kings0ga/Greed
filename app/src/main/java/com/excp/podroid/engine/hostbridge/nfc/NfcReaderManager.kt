/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Reads Android NFC tags while MainActivity is in the foreground and exposes
 * immutable, cursor-addressed snapshots to the guest through the host bridge.
 * The guest never receives a live Tag handle and cannot issue arbitrary NFC
 * writes or APDUs through this v1 interface.
 */
package com.excp.podroid.engine.hostbridge.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.excp.podroid.engine.hostbridge.HostProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class NfcReaderManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? by lazy {
        context.getSystemService(NfcManager::class.java)?.defaultAdapter
    }

    private val lifecycleLock = Any()
    private val dedupeLock = Any()
    @Volatile private var eventBuffer = NfcEventBuffer()
    @Volatile private var vmSessionActive = false
    @Volatile private var readerActive = false
    @Volatile private var lastError: String? = null
    private var activeActivity = WeakReference<Activity>(null)
    private var lastFingerprint: String? = null
    private var lastFingerprintAtMs = 0L
    private var duplicatesSuppressed = 0L

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                refreshReaderForAdapterState()
            }
        }
    }

    init {
        registerAdapterStateReceiver()
    }

    fun enableReaderMode(activity: Activity) = synchronized(lifecycleLock) {
        val previousActivity = activeActivity.get()
        if (readerActive && previousActivity != null && previousActivity !== activity) {
            runCatching { adapter?.disableReaderMode(previousActivity) }
                .onFailure { Log.w(TAG, "Failed to release NFC reader mode from old Activity", it) }
            readerActive = false
        }
        activeActivity = WeakReference(activity)
        // Reader Mode temporarily disables this phone's card-emulation mode.
        // Only claim the controller while a VM session can consume the events.
        if (!vmSessionActive) {
            readerActive = false
            lastError = null
            return@synchronized
        }
        enableReaderModeLocked(activity)
    }

    private fun enableReaderModeLocked(activity: Activity) {
        val nfc = adapter
        if (nfc == null) {
            readerActive = false
            lastError = "nfc hardware unavailable"
            return
        }
        if (!runCatching { nfc.isEnabled }.getOrDefault(false)) {
            readerActive = false
            lastError = "nfc disabled"
            return
        }
        if (readerActive && activeActivity.get() === activity) return

        runCatching {
            nfc.enableReaderMode(activity, this, READER_FLAGS, null)
        }.onSuccess {
            readerActive = true
            lastError = null
        }.onFailure { error ->
            readerActive = false
            lastError = (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
            Log.w(TAG, "Failed to enable NFC reader mode", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerAdapterStateReceiver() {
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The broadcast is sent by Android's NFC system service. The
                // receiver never trusts intent extras; it re-reads the adapter.
                context.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(adapterStateReceiver, filter)
            }
        }.onFailure { error ->
            lastError = (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
            Log.w(TAG, "Failed to observe NFC adapter state", error)
        }
    }

    private fun refreshReaderForAdapterState() = synchronized(lifecycleLock) {
        val activity = activeActivity.get()
        if (!vmSessionActive || activity == null) return@synchronized
        if (adapter?.let { runCatching { it.isEnabled }.getOrDefault(false) } == true) {
            readerActive = false
            enableReaderModeLocked(activity)
        } else {
            readerActive = false
            lastError = "nfc disabled"
        }
    }

    fun disableReaderMode(activity: Activity) = synchronized(lifecycleLock) {
        if (activeActivity.get() !== activity) return@synchronized
        runCatching { adapter?.disableReaderMode(activity) }
            .onFailure { Log.w(TAG, "Failed to disable NFC reader mode", it) }
        activeActivity.clear()
        readerActive = false
    }

    /** Starts a fresh cursor namespace for one VM lifecycle. */
    fun beginVmSession() = synchronized(lifecycleLock) {
        if (vmSessionActive) return@synchronized
        eventBuffer = NfcEventBuffer()
        vmSessionActive = true
        resetDedupe()
        activeActivity.get()?.let(::enableReaderModeLocked)
    }

    /** Drops retained events so a later VM cannot replay an old IoT action. */
    fun endVmSession() = synchronized(lifecycleLock) {
        if (!vmSessionActive) return@synchronized
        activeActivity.get()?.let { activity ->
            runCatching { adapter?.disableReaderMode(activity) }
                .onFailure { Log.w(TAG, "Failed to disable NFC reader mode at VM stop", it) }
        }
        readerActive = false
        vmSessionActive = false
        eventBuffer = NfcEventBuffer()
        resetDedupe()
    }

    override fun onTagDiscovered(tag: Tag) {
        // Bind this callback to the VM session that was active when discovery
        // began. A late binder callback must never leak a tag from a stopped VM
        // into the next VM's cursor namespace.
        val targetBuffer = synchronized(lifecycleLock) {
            if (!vmSessionActive || !readerActive) return
            eventBuffer
        }
        val seenAtMs = System.currentTimeMillis()
        val id = tag.id?.copyOf() ?: byteArrayOf()
        val technologies = tag.techList
            .map { it.substringAfterLast('.') }
            .distinct()
            .sorted()
        val ndef = snapshotNdef(tag)
        val fingerprint = buildFingerprint(id, technologies, ndef)
        val nowElapsed = SystemClock.elapsedRealtime()

        val event = synchronized(lifecycleLock) {
            if (!vmSessionActive || !readerActive || eventBuffer !== targetBuffer) return

            if (fingerprint != null) {
                synchronized(dedupeLock) {
                    if (fingerprint == lastFingerprint && nowElapsed - lastFingerprintAtMs < DEDUPE_MS) {
                        duplicatesSuppressed++
                        return
                    }
                    lastFingerprint = fingerprint
                    lastFingerprintAtMs = nowElapsed
                }
            }

            targetBuffer.append { sequence, sessionId ->
                buildBoundedEventJson(
                    sequence = sequence,
                    sessionId = sessionId,
                    seenAtMs = seenAtMs,
                    id = id,
                    technologies = technologies,
                    ndef = ndef,
                )
            }
        }
        lastError = null
        Log.d(TAG, "NFC tag buffered at ${targetBuffer.cursorFor(event.sequence)}")
    }

    fun status(): String {
        val nfc = adapter
        val available = nfc != null
        val enabled = nfc?.let { runCatching { it.isEnabled }.getOrDefault(false) } ?: false
        val buffer = eventBuffer
        val status = buffer.status()
        val json = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("available", available)
            .put("enabled", enabled)
            .put("readerActive", readerActive && enabled)
            .put("foregroundOnly", true)
            .put("vmSessionActive", vmSessionActive)
            .put("session", status.sessionId)
            .put("oldestCursor", status.oldestSequence?.let(buffer::cursorFor) ?: JSONObject.NULL)
            .put("latestCursor", buffer.cursorForLatest())
            .put("buffered", status.buffered)
            .put("capacity", status.capacity)
            .put("dropped", status.dropped)
            .put("duplicatesSuppressed", synchronized(dedupeLock) { duplicatesSuppressed })
            .put("lastError", lastError ?: JSONObject.NULL)
        return NfcBridgeResponse.okJson(json.toString())
    }

    fun current(): String {
        val buffer = eventBuffer
        val event = buffer.current()
        val json = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("cursor", event?.let { buffer.cursorFor(it.sequence) } ?: buffer.cursorForLatest())
            .put("event", event?.let { JSONObject(it.json) } ?: JSONObject.NULL)
            .put("dropped", buffer.status().dropped)
        return NfcBridgeResponse.okJson(json.toString())
    }

    fun next(cursor: String): String = try {
        val buffer = eventBuffer
        val next = buffer.next(cursor)
        val json = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("cursor", next.cursor)
            .put("event", next.event?.let { JSONObject(it.json) } ?: JSONObject.NULL)
            .put("gap", next.gap)
            .put("reset", next.reset)
            .put("dropped", next.dropped)
        NfcBridgeResponse.okJson(json.toString())
    } catch (_: IllegalArgumentException) {
        HostProtocol.err("invalid cursor")
    }

    private fun snapshotNdef(tag: Tag): NdefSnapshot? {
        val technology = runCatching { Ndef.get(tag) }.getOrNull() ?: return null
        val messageResult = runCatching { technology.cachedNdefMessage }
        val message = messageResult.getOrNull()
        return NdefSnapshot(
            type = runCatching { technology.type }.getOrNull(),
            maxSize = runCatching { technology.maxSize }.getOrNull(),
            writable = runCatching { technology.isWritable }.getOrNull(),
            message = message,
            readError = messageResult.exceptionOrNull()
                ?.let { it.message ?: it.javaClass.simpleName }
                ?.take(MAX_ERROR_CHARS),
        )
    }

    private fun buildFingerprint(
        id: ByteArray,
        technologies: List<String>,
        ndef: NdefSnapshot?,
    ): String? {
        val digest = ndef?.message?.let { sha256Hex(it.toByteArray()) }.orEmpty()
        // Some technologies expose no stable ID. Without either an ID or NDEF
        // digest there is no safe fingerprint, so do not collapse distinct tags.
        if (id.isEmpty() && digest.isEmpty()) return null
        return "${hex(id)}|${technologies.joinToString(",")}|$digest"
    }

    private fun buildBoundedEventJson(
        sequence: Long,
        sessionId: String,
        seenAtMs: Long,
        id: ByteArray,
        technologies: List<String>,
        ndef: NdefSnapshot?,
    ): String {
        val full = buildEventJson(
            sequence,
            sessionId,
            seenAtMs,
            id,
            technologies,
            ndef,
            includePayload = true,
        )
        if (NfcBridgeResponse.eventFits(full)) return full

        // Preserve the stable identifiers and full-message digest but remove
        // record payloads if nested base64 would exceed hostd's 8 KiB frame.
        val withoutPayload = buildEventJson(
            sequence,
            sessionId,
            seenAtMs,
            id,
            technologies,
            ndef,
            includePayload = false,
            bridgeTruncated = true,
        )
        if (NfcBridgeResponse.eventFits(withoutPayload)) return withoutPayload

        val summary = buildSummaryEventJson(
            sequence = sequence,
            sessionId = sessionId,
            seenAtMs = seenAtMs,
            id = id,
            technologies = technologies,
            ndef = ndef,
        )
        if (NfcBridgeResponse.eventFits(summary)) return summary

        // All variable tag-controlled data is omitted in the final fallback.
        // This should be unreachable for Android tags, but keeps the line-size
        // contract true even if a future platform supplies unexpected values.
        return JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("kind", "tag")
            .put("session", sessionId)
            .put("sequence", sequence)
            .put("seenAtMs", seenAtMs)
            .put("bridgeTruncated", true)
            .put("error", "tag metadata exceeded bridge limit")
            .toString()
    }

    private fun buildSummaryEventJson(
        sequence: Long,
        sessionId: String,
        seenAtMs: Long,
        id: ByteArray,
        technologies: List<String>,
        ndef: NdefSnapshot?,
    ): String {
        val event = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("kind", "tag")
            .put("session", sessionId)
            .put("sequence", sequence)
            .put("seenAtMs", seenAtMs)
            .put("idHex", hex(id))
            .put("idBase64", base64(id))
            .put("technologies", JSONArray(technologies))
            .put("bridgeTruncated", true)

        if (ndef == null) return event.put("ndef", JSONObject.NULL).toString()
        val messageBytes = ndef.message?.toByteArray()
        return event.put(
            "ndef",
            JSONObject()
                .put("type", ndef.type?.take(MAX_NDEF_TYPE_CHARS) ?: JSONObject.NULL)
                .put("maxSize", ndef.maxSize ?: JSONObject.NULL)
                .put("writable", ndef.writable ?: JSONObject.NULL)
                .put("canonicalLength", messageBytes?.size ?: 0)
                .put("sha256", messageBytes?.let(::sha256Hex) ?: JSONObject.NULL)
                .put("readError", ndef.readError ?: JSONObject.NULL)
                .put("bridgeTruncated", true)
                .put("records", JSONArray())
                .put("recordCount", ndef.message?.records?.size ?: 0)
                .put("truncated", true),
        ).toString()
    }

    private fun buildEventJson(
        sequence: Long,
        sessionId: String,
        seenAtMs: Long,
        id: ByteArray,
        technologies: List<String>,
        ndef: NdefSnapshot?,
        includePayload: Boolean,
        bridgeTruncated: Boolean = false,
    ): String {
        val event = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("kind", "tag")
            .put("session", sessionId)
            .put("sequence", sequence)
            .put("seenAtMs", seenAtMs)
            .put("idHex", hex(id))
            .put("idBase64", base64(id))
            .put("technologies", JSONArray(technologies))

        if (ndef == null) {
            event.put("ndef", JSONObject.NULL)
            return event.toString()
        }

        val messageBytes = ndef.message?.toByteArray()
        val ndefJson = JSONObject()
            .put("type", ndef.type?.take(MAX_NDEF_TYPE_CHARS) ?: JSONObject.NULL)
            .put("maxSize", ndef.maxSize ?: JSONObject.NULL)
            .put("writable", ndef.writable ?: JSONObject.NULL)
            .put("canonicalLength", messageBytes?.size ?: 0)
            .put("sha256", messageBytes?.let(::sha256Hex) ?: JSONObject.NULL)
            .put("readError", ndef.readError ?: JSONObject.NULL)
            .put("bridgeTruncated", bridgeTruncated)

        val recordsJson = JSONArray()
        var payloadBudget = if (includePayload) MAX_CAPTURED_PAYLOAD_BYTES else 0
        var metadataBudget = MAX_CAPTURED_METADATA_BYTES
        var previewBudget = if (includePayload) MAX_PREVIEW_BYTES else 0
        var anyRecordTruncated = false
        val records = ndef.message?.records.orEmpty()
        records.take(MAX_NDEF_RECORDS).forEach { record ->
            val payload = record.payload ?: byteArrayOf()
            val capturedLength = min(payload.size, payloadBudget)
            val captured = if (capturedLength > 0) payload.copyOf(capturedLength) else byteArrayOf()
            payloadBudget -= capturedLength

            val type = record.type ?: byteArrayOf()
            val capturedTypeLength = min(type.size, metadataBudget)
            val capturedType = if (capturedTypeLength > 0) type.copyOf(capturedTypeLength) else byteArrayOf()
            metadataBudget -= capturedTypeLength

            val id = record.id ?: byteArrayOf()
            val capturedIdLength = min(id.size, metadataBudget)
            val capturedId = if (capturedIdLength > 0) id.copyOf(capturedIdLength) else byteArrayOf()
            metadataBudget -= capturedIdLength
            val recordTruncated = capturedLength < payload.size ||
                capturedTypeLength < type.size || capturedIdLength < id.size
            anyRecordTruncated = anyRecordTruncated || recordTruncated

            val recordJson = JSONObject()
                .put("tnf", record.tnf.toInt())
                .put("typeLength", type.size)
                .put("typeBase64", base64(capturedType))
                .put("idLength", id.size)
                .put("idBase64", base64(capturedId))
                .put("payloadLength", payload.size)
                .put("payloadBase64", base64(captured))
                .put("truncated", recordTruncated)
                .put(
                    "mimeType",
                    runCatching { record.toMimeType() }.getOrNull()?.take(MAX_MIME_CHARS)
                        ?: JSONObject.NULL,
                )

            if (previewBudget > 0) {
                val preview = recordPreview(record)?.take(previewBudget)
                if (!preview.isNullOrEmpty()) {
                    recordJson.put("preview", preview)
                    previewBudget -= preview.length
                }
            }
            recordsJson.put(recordJson)
        }
        ndefJson
            .put("records", recordsJson)
            .put("recordCount", records.size)
            .put(
                "truncated",
                bridgeTruncated || anyRecordTruncated || records.size > MAX_NDEF_RECORDS ||
                    records.sumOf { it.payload?.size ?: 0 } > MAX_CAPTURED_PAYLOAD_BYTES,
            )
        event.put("ndef", ndefJson)
        return event.toString()
    }

    private fun recordPreview(record: NdefRecord): String? {
        runCatching { record.toUri()?.toString() }.getOrNull()?.let { return it }
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN ||
            !record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload ?: return null
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xff
        val languageLength = status and 0x3f
        val textOffset = 1 + languageLength
        if (textOffset > payload.size) return null
        val charset = if (status and 0x80 != 0) Charset.forName("UTF-16") else Charsets.UTF_8
        return runCatching { String(payload, textOffset, payload.size - textOffset, charset) }.getOrNull()
    }

    private fun resetDedupe() = synchronized(dedupeLock) {
        lastFingerprint = null
        lastFingerprintAtMs = 0L
        duplicatesSuppressed = 0L
    }

    private data class NdefSnapshot(
        val type: String?,
        val maxSize: Int?,
        val writable: Boolean?,
        val message: NdefMessage?,
        val readError: String?,
    )

    companion object {
        private const val TAG = "NfcReaderManager"
        private const val SCHEMA_VERSION = 1
        private const val DEDUPE_MS = 1_000L
        private const val MAX_CAPTURED_PAYLOAD_BYTES = 2_048
        private const val MAX_CAPTURED_METADATA_BYTES = 512
        private const val MAX_PREVIEW_BYTES = 512
        private const val MAX_NDEF_RECORDS = 16
        private const val MAX_ERROR_CHARS = 256
        private const val MAX_MIME_CHARS = 128
        private const val MAX_NDEF_TYPE_CHARS = 128

        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE

        private fun base64(bytes: ByteArray): String =
            Base64.getEncoder().encodeToString(bytes)

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }

        private fun sha256Hex(bytes: ByteArray): String =
            hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
