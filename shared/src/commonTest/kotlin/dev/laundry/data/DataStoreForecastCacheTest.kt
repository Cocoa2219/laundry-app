package dev.laundry.data

import dev.laundry.weather.CachedForecast
import dev.laundry.weather.GeoPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DataStoreForecastCacheTest {

    @Test
    fun empty_readsNull() = runTest {
        assertNull(DataStoreForecastCache(InMemoryPreferencesStore()).read())
    }

    @Test
    fun write_thenRead_roundTrips() = runTest {
        val cache = DataStoreForecastCache(InMemoryPreferencesStore())
        val entry = CachedForecast(GeoPoint(37.57, 126.98), """{"x":1}""", Instant.fromEpochMilliseconds(1_793_190_000_123))

        cache.write(entry)

        assertEquals(entry, cache.read())
    }

    @Test
    fun secondWrite_replacesFirst() = runTest {
        val cache = DataStoreForecastCache(InMemoryPreferencesStore())
        val second = CachedForecast(GeoPoint(35.18, 129.08), "b", Instant.fromEpochMilliseconds(2))

        cache.write(CachedForecast(GeoPoint(37.57, 126.98), "a", Instant.fromEpochMilliseconds(1)))
        cache.write(second)

        assertEquals(second, cache.read())
    }
}
