package dev.laundry.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.laundry.data.DataStoreForecastCache
import dev.laundry.data.FeedbackRepository
import dev.laundry.data.LaundryDatabase
import dev.laundry.data.LoadPreferences
import dev.laundry.data.LocationRepository
import dev.laundry.weather.OpenMeteoClient
import dev.laundry.weather.WeatherRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers

/** Builds every app-wide dependency once, by hand. Lives as long as the Application. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val httpClient = HttpClient(OkHttp)

    // DataStore requires exactly one instance per file for the whole process.
    private val settingsStore = PreferenceDataStoreFactory.create {
        appContext.filesDir.resolve("datastore/settings.preferences_pb")
    }
    private val weatherCacheStore = PreferenceDataStoreFactory.create {
        appContext.filesDir.resolve("datastore/weather_cache.preferences_pb")
    }

    private val openMeteoClient = OpenMeteoClient(httpClient)

    val weatherRepository = WeatherRepository(openMeteoClient, DataStoreForecastCache(weatherCacheStore))

    val locationRepository = LocationRepository(settingsStore, AndroidDeviceLocationSource(appContext))

    val loadPreferences = LoadPreferences(settingsStore)

    private val database = Room.databaseBuilder<LaundryDatabase>(
        context = appContext,
        name = appContext.getDatabasePath("laundry.db").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    val feedbackRepository = FeedbackRepository(database, weatherRepository)

    suspend fun searchPlaces(name: String) = openMeteoClient.searchPlaces(name)
}
