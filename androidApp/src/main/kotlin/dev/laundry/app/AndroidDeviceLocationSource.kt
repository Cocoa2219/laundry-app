package dev.laundry.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
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

        // A town-level fix is enough for a forecast, so a recent cached fix is used when the
        // network provider cannot produce a fresh one in time.
        val fresh = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            withTimeoutOrNull(FIX_TIMEOUT_MS) { currentFix(manager, LocationManager.NETWORK_PROVIDER) }
        } else {
            null
        }
        val location = fresh
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ?: return null
        return GeoPoint(location.latitude, location.longitude)
    }

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
    }
}
