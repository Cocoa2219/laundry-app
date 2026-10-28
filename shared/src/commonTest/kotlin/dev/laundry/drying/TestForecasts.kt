package dev.laundry.drying

import dev.laundry.weather.Forecast
import dev.laundry.weather.HourlyWeather
import dev.laundry.weather.SunTimes
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

val DAY: LocalDate = LocalDate(2026, 7, 1)

fun at(hour: Int, minute: Int = 0, date: LocalDate = DAY) = LocalDateTime(date, LocalTime(hour, minute))

fun weather(
    tempC: Double = 25.0,
    rhPercent: Double = 50.0,
    windMs: Double = 2.0,
    radiationWm2: Double = 100.0,
    rainProb: Int = 0,
    rainMm: Double = 0.0,
) = HourlyWeather(at(0), tempC, rhPercent, windMs, radiationWm2, rainProb, rainMm)

/**
 * Two days of identical hourly [base] weather starting at 00:00 on [DAY], with sunset at
 * [sunsetHour]. [overrides] replace single hours, keyed by hours since the first one.
 */
fun forecast(
    base: HourlyWeather = weather(),
    sunsetHour: Int = 19,
    overrides: Map<Int, HourlyWeather> = emptyMap(),
    missingHours: Set<Int> = emptySet(),
): Forecast {
    val hourly = (0 until 48)
        .filterNot { it in missingHours }
        .map { i ->
            val date = if (i < 24) DAY else LocalDate(2026, 7, 2)
            (overrides[i] ?: base).copy(time = at(i % 24, date = date))
        }
    val sun = listOf(DAY, LocalDate(2026, 7, 2)).map { date ->
        SunTimes(date, sunrise = at(5, date = date), sunset = at(sunsetHour, date = date))
    }
    return Forecast(hourly, sun, "Asia/Seoul", Instant.fromEpochSeconds(0), fromCache = false)
}

fun SimulationResult.hours(): Double = minutesBetween(start, finish!!) / 60
