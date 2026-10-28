package dev.laundry.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.laundry.weather.GeoPoint
import dev.laundry.weather.Place
import kotlinx.coroutines.flow.first

/** Platform hook for the device position; null when permission is missing or no fix is available. */
fun interface DeviceLocationSource {
    suspend fun currentLocation(): GeoPoint?
}

sealed interface LocationChoice {
    data object Device : LocationChoice
    data class City(val place: Place) : LocationChoice
}

sealed interface ResolvedLocation {
    /** [cityName] is null when the point came from the device. */
    data class Found(val point: GeoPoint, val cityName: String?) : ResolvedLocation

    /** Device location is unavailable and no city is set: the UI should ask for one. */
    data object NeedsCity : ResolvedLocation
}

/** The single location the app forecasts for: the device position, or one fixed city. */
class LocationRepository(
    private val store: DataStore<Preferences>,
    private val device: DeviceLocationSource,
) {
    suspend fun getChoice(): LocationChoice {
        val prefs = store.data.first()
        val place = Place(
            name = prefs[CITY_NAME] ?: return LocationChoice.Device,
            region = prefs[CITY_REGION],
            country = prefs[CITY_COUNTRY],
            point = GeoPoint(
                latitude = prefs[CITY_LATITUDE] ?: return LocationChoice.Device,
                longitude = prefs[CITY_LONGITUDE] ?: return LocationChoice.Device,
            ),
        )
        return LocationChoice.City(place)
    }

    suspend fun useCity(place: Place) {
        store.edit {
            it[CITY_NAME] = place.name
            it[CITY_LATITUDE] = place.point.latitude
            it[CITY_LONGITUDE] = place.point.longitude
            if (place.region != null) it[CITY_REGION] = place.region else it.remove(CITY_REGION)
            if (place.country != null) it[CITY_COUNTRY] = place.country else it.remove(CITY_COUNTRY)
        }
    }

    suspend fun useDevice() {
        store.edit {
            it.remove(CITY_NAME)
            it.remove(CITY_REGION)
            it.remove(CITY_COUNTRY)
            it.remove(CITY_LATITUDE)
            it.remove(CITY_LONGITUDE)
        }
    }

    suspend fun resolve(): ResolvedLocation =
        when (val choice = getChoice()) {
            is LocationChoice.City -> ResolvedLocation.Found(choice.place.point, choice.place.name)
            LocationChoice.Device ->
                device.currentLocation()?.let { ResolvedLocation.Found(it, cityName = null) }
                    ?: ResolvedLocation.NeedsCity
        }

    private companion object {
        val CITY_NAME = stringPreferencesKey("city_name")
        val CITY_REGION = stringPreferencesKey("city_region")
        val CITY_COUNTRY = stringPreferencesKey("city_country")
        val CITY_LATITUDE = doublePreferencesKey("city_latitude")
        val CITY_LONGITUDE = doublePreferencesKey("city_longitude")
    }
}
