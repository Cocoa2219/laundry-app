package dev.laundry.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

data class GeoPoint(val latitude: Double, val longitude: Double)

data class HourlyWeather(
    val time: LocalDateTime,
    val tempC: Double,
    val rhPercent: Double,
    val windMs: Double,        // at 10 m
    val radiationWm2: Double,  // shortwave
    val rainProb: Int,         // %
    val rainMm: Double,
)

data class SunTimes(
    val date: LocalDate,
    val sunrise: LocalDateTime,
    val sunset: LocalDateTime,
)

/** Hourly weather and sun times in the location's local time zone. */
data class Forecast(
    val hourly: List<HourlyWeather>,
    val sun: List<SunTimes>,
    val timezone: String,
    val fetchedAt: Instant,
    val fromCache: Boolean,
)

/** A geocoding result the user can pick as their fixed location. */
data class Place(
    val name: String,
    val region: String?,
    val country: String?,
    val point: GeoPoint,
)

/** Thrown when there is neither a fresh forecast nor a usable cached one. */
class WeatherUnavailableException(cause: Throwable) :
    Exception("No forecast available and no cached forecast for this location", cause)
