package dev.laundry.weather

import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/** The last successful forecast response, stored as received. */
data class CachedForecast(
    val point: GeoPoint,
    val json: String,
    val fetchedAt: Instant,
)

interface ForecastCache {
    suspend fun read(): CachedForecast?
    suspend fun write(entry: CachedForecast)
}

class WeatherRepository(
    private val client: OpenMeteoClient,
    private val cache: ForecastCache,
    private val clock: Clock = Clock.System,
) {
    /**
     * Fetches a fresh forecast and caches it. When the fetch or parse fails, falls back to the
     * cached forecast for the same location; otherwise throws [WeatherUnavailableException].
     */
    suspend fun getForecast(point: GeoPoint): Forecast {
        val fetchedAt = clock.now()
        return try {
            val json = client.fetchForecastJson(point)
            val forecast = parse(json, fetchedAt, fromCache = false)
            cache.write(CachedForecast(point, json, fetchedAt))
            forecast
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cachedForecastFor(point) ?: throw WeatherUnavailableException(e)
        }
    }

    private suspend fun cachedForecastFor(point: GeoPoint): Forecast? {
        val cached = cache.read() ?: return null
        if (!cached.point.isNear(point)) return null
        return parse(cached.json, cached.fetchedAt, fromCache = true)
    }

    private fun parse(json: String, fetchedAt: Instant, fromCache: Boolean): Forecast =
        openMeteoJson.decodeFromString<ForecastDto>(json).toForecast(fetchedAt, fromCache)
}

// 0.05° is about 5 km: device fixes jitter between launches, but a forecast for
// another town should not be reused.
private fun GeoPoint.isNear(other: GeoPoint): Boolean =
    abs(latitude - other.latitude) <= 0.05 && abs(longitude - other.longitude) <= 0.05
