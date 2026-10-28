package dev.laundry.drying

import dev.laundry.weather.Forecast
import dev.laundry.weather.HourlyWeather
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes

enum class CutoffReason {
    /** Rain is expected at an exposed spot; laundry must be in before the hour starts. */
    RAIN,

    /** One hour after sunset: no meaningful drying outdoors after that. */
    NIGHT,

    /** The forecast has no weather for an hour that is needed. */
    NO_DATA,
}

data class Cutoff(val time: LocalDateTime, val reason: CutoffReason)

data class SimulationResult(
    val start: LocalDateTime,
    /** When the load is dry, or null if a cutoff comes first. */
    val finish: LocalDateTime?,
    /** The first cutoff after [start]; null indoors, where nothing cuts drying off. */
    val cutoff: Cutoff?,
) {
    val dries: Boolean get() = finish != null
}

/**
 * Steps through the forecast from [start], accumulating `rate · correction` until the load's
 * threshold is reached or the first cutoff arrives.
 *
 * Each forecast hour's rate applies for that whole hour; partial hours (a start or cutoff
 * between full hours, or the finish inside an hour) are prorated linearly.
 */
fun simulate(
    forecast: Forecast,
    load: Load,
    start: LocalDateTime,
    correction: Double = 1.0,
): SimulationResult {
    if (load.spot == Spot.INDOOR) {
        val hours = load.threshold / (INDOOR_RATE * correction)
        return SimulationResult(start, start.plusMinutes(hours * 60), cutoff = null)
    }

    val cutoff = firstCutoff(forecast, load.spot, start)
    val weatherByHour = forecast.hourly.associateBy { it.time }
    var time = start
    var progress = 0.0

    while (cutoff == null || time < cutoff.time) {
        val hourStart = time.truncatedToHour()
        val weather = weatherByHour[hourStart]
            ?: return SimulationResult(start, null, Cutoff(time, CutoffReason.NO_DATA))
        val hourEnd = hourStart.plusMinutes(60.0)
        val segmentEnd = if (cutoff != null && cutoff.time < hourEnd) cutoff.time else hourEnd

        val ratePerHour = dryingRate(weather, load.spot) * correction
        val remaining = load.threshold - progress
        val gain = ratePerHour * minutesBetween(time, segmentEnd) / 60
        if (gain >= remaining) {
            return SimulationResult(start, time.plusMinutes(remaining / ratePerHour * 60), cutoff)
        }
        progress += gain
        time = segmentEnd
    }
    return SimulationResult(start, null, cutoff)
}

/** The earliest rain or night cutoff on or after [start]'s hour, or null without sun data. */
internal fun firstCutoff(forecast: Forecast, spot: Spot, start: LocalDateTime): Cutoff? {
    val night = forecast.sun.firstOrNull { it.date == start.date }
        ?.let { Cutoff(it.sunset.plusMinutes(60.0), CutoffReason.NIGHT) }

    val rain = if (spot.rainExposed) {
        forecast.hourly
            .firstOrNull { it.isRainy() && it.time.plusMinutes(60.0) > start }
            ?.let { Cutoff(it.time, CutoffReason.RAIN) }
    } else {
        null
    }

    return listOfNotNull(night, rain).minByOrNull { it.time }
}

private fun HourlyWeather.isRainy(): Boolean = rainProb >= 50 || rainMm > 0.1

// Forecast times are local wall-clock times. Treating them as UTC keeps the arithmetic
// simple and is exact except across a daylight-saving change (Korea has none).
internal fun LocalDateTime.plusMinutes(minutes: Double): LocalDateTime =
    toInstant(TimeZone.UTC).plus(minutes.roundToLong().minutes).toLocalDateTime(TimeZone.UTC)

internal fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Double =
    (to.toInstant(TimeZone.UTC) - from.toInstant(TimeZone.UTC)).inWholeSeconds / 60.0

internal fun LocalDateTime.truncatedToHour(): LocalDateTime = LocalDateTime(date, LocalTime(hour, 0))
