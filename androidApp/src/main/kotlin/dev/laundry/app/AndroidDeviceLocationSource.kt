package dev.laundry.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.laundry.data.DeviceLocationSource
import dev.laundry.weather.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Coarse device position from the platform LocationManager. Plain LocationManager rather than
 * Play Services so it also works on devices without Google services (e.g. Windows Subsystem
 * for Android).
 */
class AndroidDeviceLocationSource(private val context: Context) : DeviceLocationSource {

    @SuppressLint("MissingPermission") // checked by hasPermission() before any location call
    override suspend fun currentLocation(): GeoPoint? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        // A town-level fix is enough for a forecast, so a fix from the last hour is used straight
        // away. Only without one is a fresh fix requested, and an older fix is the last resort.
        // (Devices without a network provider, such as Windows Subsystem for Android, otherwise
        // wait out the whole timeout on every launch.)
        val lastKnown = lastKnownFix(manager)
        val location = lastKnown?.takeIf { it.ageMs() <= RECENT_FIX_MAX_AGE_MS }
            ?: freshNetworkFix(manager)
            ?: lastKnown
            ?: return null
        return GeoPoint(location.latitude, location.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownFix(manager: LocationManager): Location? =
        listOfNotNull(
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            // Passive also returns fixes other apps or the host obtained, coarsened for this app.
            manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER),
        ).minByOrNull { it.ageMs() }

    private suspend fun freshNetworkFix(manager: LocationManager): Location? {
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) { currentFix(manager, LocationManager.NETWORK_PROVIDER) }
    }

    private fun Location.ageMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location -> continuation.resume(location) }
        }

    private companion object {
        const val FIX_TIMEOUT_MS = 10_000L
        const val RECENT_FIX_MAX_AGE_MS = 60 * 60 * 1000L
    }
}
