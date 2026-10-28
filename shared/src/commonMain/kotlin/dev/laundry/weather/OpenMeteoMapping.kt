package dev.laundry.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.time.Instant

// JMA MSM (5 km) covers Korea and Japan and is finer than the global blend for
// humidity, wind and sun, but it has no precipitation probability. best_match fills
// that field everywhere and replaces MSM entirely outside its domain.
internal const val PRIMARY_MODEL = "jma_msm"
internal const val FALLBACK_MODEL = "best_match"
internal const val FORECAST_MODELS = "$PRIMARY_MODEL,$FALLBACK_MODEL"

internal fun ForecastDto.toForecast(fetchedAt: Instant, fromCache: Boolean): Forecast =
    Forecast(
        hourly = hourly.toHourlyWeather(),
        sun = daily.toSunTimes(),
        timezone = timezone,
        fetchedAt = fetchedAt,
        fromCache = fromCache,
    )

private fun JsonObject.toHourlyWeather(): List<HourlyWeather> {
    val time = blended("time")
    val temp = blended("temperature_2m")
    val rh = blended("relative_humidity_2m")
    val wind = blended("wind_speed_10m")
    val radiation = blended("shortwave_radiation")
    val rainProb = blended("precipitation_probability")
    val rainMm = blended("precipitation")

    return (0 until time.size).mapNotNull { i ->
        // An hour without the four drying inputs cannot be simulated, so it is dropped.
        HourlyWeather(
            time = LocalDateTime.parse(time.string(i) ?: return@mapNotNull null),
            tempC = temp.double(i) ?: return@mapNotNull null,
            rhPercent = rh.double(i) ?: return@mapNotNull null,
            windMs = wind.double(i) ?: return@mapNotNull null,
            radiationWm2 = radiation.double(i) ?: return@mapNotNull null,
            // Missing rain data counts as no rain; the other rain field can still trigger the cutoff.
            rainProb = rainProb.double(i)?.roundToInt() ?: 0,
            rainMm = rainMm.double(i) ?: 0.0,
        )
    }
}

private fun JsonObject.toSunTimes(): List<SunTimes> {
    val date = blended("time")
    val sunrise = blended("sunrise")
    val sunset = blended("sunset")
    return (0 until date.size).mapNotNull { i ->
        SunTimes(
            date = LocalDate.parse(date.string(i) ?: return@mapNotNull null),
            sunrise = LocalDateTime.parse(sunrise.string(i) ?: return@mapNotNull null),
            sunset = LocalDateTime.parse(sunset.string(i) ?: return@mapNotNull null),
        )
    }
}

/**
 * One field across models, taking the first non-null value per index.
 *
 * With several models Open-Meteo suffixes each key (`temperature_2m_jma_msm`); when only
 * one model covers the location it returns the plain key (`temperature_2m`). Shared keys
 * such as `time` are never suffixed. Checking all three names handles every shape.
 */
private class BlendedSeries(private val arrays: List<JsonArray>) {
    val size: Int get() = arrays.maxOfOrNull { it.size } ?: 0

    fun double(i: Int): Double? =
        arrays.firstNotNullOfOrNull { it.getOrNull(i)?.jsonPrimitive?.doubleOrNull }

    fun string(i: Int): String? =
        arrays.firstNotNullOfOrNull { it.getOrNull(i)?.jsonPrimitive?.contentOrNull }
}

private fun JsonObject.blended(field: String): BlendedSeries =
    BlendedSeries(
        listOf("${field}_$PRIMARY_MODEL", "${field}_$FALLBACK_MODEL", field)
            .mapNotNull { this[it] as? JsonArray },
    )

internal fun PlaceDto.toPlace(): Place =
    Place(
        name = name,
        region = admin1,
        country = country,
        point = GeoPoint(latitude, longitude),
    )
