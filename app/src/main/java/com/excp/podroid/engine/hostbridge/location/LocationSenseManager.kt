package com.excp.podroid.engine.hostbridge.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.hostbridge.HostProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationSenseManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: LocationManager by lazy {
        context.getSystemService(LocationManager::class.java)
    }
    private var reverseCache: ReverseCache? = null
    private var lastReverseRequestAtMs = 0L

    fun status(): String =
        HostProtocol.ok(HostProtocol.enc(statusJson(lastKnownLocation())))

    suspend fun current(): String {
        val reading = currentLocationReading()
        if (reading.error != null) return HostProtocol.err(reading.error)
        return HostProtocol.ok(HostProtocol.enc(locationJson(reading.location!!, fresh = reading.fresh)))
    }

    suspend fun address(): String {
        val reading = currentLocationReading()
        if (reading.error != null) return HostProtocol.err(reading.error)
        val location = reading.location!!
        cachedReverseAddress(location)?.let { cached ->
            return HostProtocol.ok(HostProtocol.enc(addressJson(location, reading.fresh, JSONObject(cached), cached = true)))
        }

        return try {
            throttleReverseGeocode()
            val body = reverseGeocode(location)
            reverseCache = ReverseCache(
                latitude = location.latitude,
                longitude = location.longitude,
                responseBody = body,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            HostProtocol.ok(HostProtocol.enc(addressJson(location, reading.fresh, JSONObject(body), cached = false)))
        } catch (e: Exception) {
            HostProtocol.err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun bestProvider(): String? {
        if (!hasLocationPermission()) return null
        val enabled = runCatching { manager.getProviders(true).toSet() }.getOrDefault(emptySet())
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstOrNull { it in enabled }
    }

    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun currentLocationReading(): LocationReading {
        if (!hasLocationPermission()) return LocationReading(error = "location permission not granted")
        val provider = bestProvider() ?: return LocationReading(error = "no enabled location provider")
        val fresh = withTimeoutOrNull(CURRENT_TIMEOUT_MS) { requestSingleLocation(provider) }
        val location = fresh ?: lastKnownLocation()
            ?: return LocationReading(error = "location unavailable")
        return LocationReading(location = location, fresh = fresh != null)
    }

    private suspend fun requestSingleLocation(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                override fun onProviderDisabled(provider: String) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(null)
                }

                @Deprecated("Deprecated by Android framework")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (_: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun cachedReverseAddress(location: Location): String? {
        val cache = reverseCache ?: return null
        if (SystemClock.elapsedRealtime() - cache.elapsedRealtimeMs > ADDRESS_CACHE_MS) return null
        val distance = FloatArray(1)
        Location.distanceBetween(cache.latitude, cache.longitude, location.latitude, location.longitude, distance)
        return if (distance[0] <= ADDRESS_CACHE_METERS) cache.responseBody else null
    }

    private suspend fun throttleReverseGeocode() {
        val waitMs = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val nextAllowed = lastReverseRequestAtMs + REVERSE_MIN_INTERVAL_MS
            val wait = (nextAllowed - now).coerceAtLeast(0L)
            lastReverseRequestAtMs = now + wait
            wait
        }
        if (waitMs > 0) delay(waitMs)
    }

    private suspend fun reverseGeocode(location: Location): String = withContext(Dispatchers.IO) {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2&addressdetails=1&zoom=18&layer=address" +
                "&lat=${location.latitude}&lon=${location.longitude}",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = GEOCODER_TIMEOUT_MS.toInt()
            readTimeout = GEOCODER_TIMEOUT_MS.toInt()
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "en")
            setRequestProperty("User-Agent", NOMINATIM_USER_AGENT)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("geocoder http $code")
            if (body.isBlank()) error("empty geocoder response")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun statusJson(location: Location?): String = buildString {
        append('{')
        append("\"permission\":").append(hasLocationPermission())
        append(",\"providers\":").append(providersJson())
        append(",\"lastKnown\":")
        if (location == null) append("null") else append(locationJsonBody(location, fresh = false))
        append('}')
    }

    private fun locationJson(location: Location, fresh: Boolean): String = buildString {
        append(locationJsonBody(location, fresh))
    }

    private fun locationJsonBody(location: Location, fresh: Boolean): String = buildString {
        append('{')
        appendLocationFields(location, fresh)
        append('}')
    }

    private fun addressJson(location: Location, fresh: Boolean, geocoder: JSONObject, cached: Boolean): String = buildString {
        append('{')
        appendLocationFields(location, fresh)
        append(",\"geocoder\":\"nominatim.openstreetmap.org\"")
        append(",\"cached\":").append(cached)
        append(",\"displayName\":\"").append(jsonEscape(geocoder.optString("display_name"))).append('"')
        geocoder.optJSONObject("address")?.let {
            append(",\"address\":").append(it)
        }
        append(",\"attribution\":\"Data (c) OpenStreetMap contributors, ODbL 1.0\"")
        append('}')
    }

    private fun StringBuilder.appendLocationFields(location: Location, fresh: Boolean) {
        append("\"provider\":\"").append(jsonEscape(location.provider ?: "unknown")).append('"')
        append(",\"fresh\":").append(fresh)
        append(",\"latitude\":").append(location.latitude)
        append(",\"longitude\":").append(location.longitude)
        if (location.hasAccuracy()) append(",\"accuracyMeters\":").append(location.accuracy)
        if (location.hasAltitude()) append(",\"altitudeMeters\":").append(location.altitude)
        if (location.hasSpeed()) append(",\"speedMetersPerSecond\":").append(location.speed)
        if (location.hasBearing()) append(",\"bearingDegrees\":").append(location.bearing)
        append(",\"time\":").append(location.time)
    }

    private fun providersJson(): String {
        val enabled = runCatching { manager.getProviders(true).toSet() }.getOrDefault(emptySet())
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).joinToString(prefix = "[", postfix = "]") { provider ->
            "{\"name\":\"${jsonEscape(provider)}\",\"enabled\":${provider in enabled}}"
        }
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private data class LocationReading(
        val location: Location? = null,
        val fresh: Boolean = false,
        val error: String? = null,
    )

    private data class ReverseCache(
        val latitude: Double,
        val longitude: Double,
        val responseBody: String,
        val elapsedRealtimeMs: Long,
    )

    companion object {
        private const val CURRENT_TIMEOUT_MS = 2_000L
        private const val GEOCODER_TIMEOUT_MS = 5_000L
        private const val REVERSE_MIN_INTERVAL_MS = 1_100L
        private const val ADDRESS_CACHE_MS = 60_000L
        private const val ADDRESS_CACHE_METERS = 25f
        private const val NOMINATIM_USER_AGENT = "GreedPodroid/1.0 (https://github.com/b8kings0ga/Greed)"
    }
}
