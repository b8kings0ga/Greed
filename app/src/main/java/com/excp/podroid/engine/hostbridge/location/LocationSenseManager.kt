package com.excp.podroid.engine.hostbridge.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.hostbridge.HostProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

    fun status(): String =
        HostProtocol.ok(HostProtocol.enc(statusJson(lastKnownLocation())))

    suspend fun current(): String {
        if (!hasLocationPermission()) return HostProtocol.err("location permission not granted")
        val provider = bestProvider() ?: return HostProtocol.err("no enabled location provider")
        val fresh = withTimeoutOrNull(CURRENT_TIMEOUT_MS) { requestSingleLocation(provider) }
        val location = fresh ?: lastKnownLocation()
            ?: return HostProtocol.err("location unavailable")
        return HostProtocol.ok(HostProtocol.enc(locationJson(location, fresh = fresh != null)))
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
        append("\"provider\":\"").append(jsonEscape(location.provider ?: "unknown")).append('"')
        append(",\"fresh\":").append(fresh)
        append(",\"latitude\":").append(location.latitude)
        append(",\"longitude\":").append(location.longitude)
        if (location.hasAccuracy()) append(",\"accuracyMeters\":").append(location.accuracy)
        if (location.hasAltitude()) append(",\"altitudeMeters\":").append(location.altitude)
        if (location.hasSpeed()) append(",\"speedMetersPerSecond\":").append(location.speed)
        if (location.hasBearing()) append(",\"bearingDegrees\":").append(location.bearing)
        append(",\"time\":").append(location.time)
        append('}')
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

    companion object {
        private const val CURRENT_TIMEOUT_MS = 2_000L
    }
}
