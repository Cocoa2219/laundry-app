package dev.laundry.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.laundry.weather.CachedForecast
import dev.laundry.weather.ForecastCache
import dev.laundry.weather.GeoPoint
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** Keeps only the last successful forecast response; each write replaces it. */
class DataStoreForecastCache(private val store: DataStore<Preferences>) : ForecastCache {

    override suspend fun read(): CachedForecast? {
        val prefs = store.data.first()
        return CachedForecast(
            point = GeoPoint(prefs[LATITUDE] ?: return null, prefs[LONGITUDE] ?: return null),
            json = prefs[JSON] ?: return null,
            fetchedAt = Instant.fromEpochMilliseconds(prefs[FETCHED_AT] ?: return null),
        )
    }

    override suspend fun write(entry: CachedForecast) {
        store.edit {
            it[LATITUDE] = entry.point.latitude
            it[LONGITUDE] = entry.point.longitude
            it[JSON] = entry.json
            it[FETCHED_AT] = entry.fetchedAt.toEpochMilliseconds()
        }
    }

    private companion object {
        val LATITUDE = doublePreferencesKey("forecast_latitude")
        val LONGITUDE = doublePreferencesKey("forecast_longitude")
        val JSON = stringPreferencesKey("forecast_json")
        val FETCHED_AT = longPreferencesKey("forecast_fetched_at_ms")
    }
}
