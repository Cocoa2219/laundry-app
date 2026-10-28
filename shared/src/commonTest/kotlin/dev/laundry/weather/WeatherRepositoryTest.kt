package dev.laundry.weather

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WeatherRepositoryTest {
    private val now = Instant.fromEpochSeconds(1_793_190_000)
    private val earlier = Instant.fromEpochSeconds(1_793_100_000)

    private class InMemoryCache(var entry: CachedForecast? = null) : ForecastCache {
        override suspend fun read() = entry
        override suspend fun write(entry: CachedForecast) {
            this.entry = entry
        }
    }

    private val online = mockClient { respondJson(MULTI_MODEL_JSON) }
    private val offline = mockClient { throw RuntimeException("no network") }

    @Test
    fun success_returnsLiveForecastAndCachesRawJson() = runTest {
        val cache = InMemoryCache()
        val repo = WeatherRepository(online, cache, FixedClock(now))

        val forecast = repo.getForecast(SEOUL)

        assertFalse(forecast.fromCache)
        assertEquals(now, forecast.fetchedAt)
        assertEquals(CachedForecast(SEOUL, MULTI_MODEL_JSON, now), cache.entry)
    }

    @Test
    fun offline_fallsBackToCacheForSameLocation() = runTest {
        // A device fix a few hundred metres away still counts as the same location.
        val nearby = GeoPoint(SEOUL.latitude + 0.01, SEOUL.longitude - 0.01)
        val cache = InMemoryCache(CachedForecast(SEOUL, MULTI_MODEL_JSON, earlier))
        val repo = WeatherRepository(offline, cache, FixedClock(now))

        val forecast = repo.getForecast(nearby)

        assertTrue(forecast.fromCache)
        assertEquals(earlier, forecast.fetchedAt)
        assertEquals(3, forecast.hourly.size)
    }

    @Test
    fun offline_cacheForAnotherCity_isNotUsed() = runTest {
        val cache = InMemoryCache(CachedForecast(SEOUL, MULTI_MODEL_JSON, earlier))
        val repo = WeatherRepository(offline, cache, FixedClock(now))

        assertFailsWith<WeatherUnavailableException> { repo.getForecast(BUSAN) }
    }

    @Test
    fun serverError_fallsBackToCache() = runTest {
        val failing = mockClient { respondJson("", HttpStatusCode.ServiceUnavailable) }
        val cache = InMemoryCache(CachedForecast(SEOUL, MULTI_MODEL_JSON, earlier))
        val repo = WeatherRepository(failing, cache, FixedClock(now))

        assertTrue(repo.getForecast(SEOUL).fromCache)
    }

    @Test
    fun malformedResponse_fallsBackAndKeepsGoodCache() = runTest {
        val garbage = mockClient { respondJson("<html>maintenance</html>") }
        val good = CachedForecast(SEOUL, MULTI_MODEL_JSON, earlier)
        val cache = InMemoryCache(good)
        val repo = WeatherRepository(garbage, cache, FixedClock(now))

        assertTrue(repo.getForecast(SEOUL).fromCache)
        assertEquals(good, cache.entry)
    }

    @Test
    fun offline_noCache_throwsWithCause() = runTest {
        val cache = InMemoryCache()
        val repo = WeatherRepository(offline, cache, FixedClock(now))

        val e = assertFailsWith<WeatherUnavailableException> { repo.getForecast(SEOUL) }
        assertEquals("no network", e.cause?.message)
        assertNull(cache.entry)
    }
}
