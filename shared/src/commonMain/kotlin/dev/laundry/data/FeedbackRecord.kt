package dev.laundry.data

import dev.laundry.drying.Load
import dev.laundry.weather.GeoPoint
import dev.laundry.weather.HourlyWeather
import kotlinx.datetime.LocalDateTime

enum class DryResult { DRY, STILL_DAMP, SKIPPED }

enum class WeatherSource {
    /** Re-fetched with `past_days` when the feedback was saved: closest to what happened. */
    PAST_DAYS,

    /** The forecast taken at hang time, kept because the re-fetch failed. */
    FORECAST,
}

/**
 * One load the user hung, and later how it went.
 *
 * The weather hours are stored as such rather than as a feature vector, so the features can
 * change later without re-fetching history.
 */
data class FeedbackRecord(
    val id: Long,
    val load: Load,
    val point: GeoPoint,
    val hangTime: LocalDateTime,
    /** Physics-only finish (correction 1.0); null when physics said it would not dry before a cutoff. */
    val predictedFinish: LocalDateTime?,
    /** Null until the user answers. */
    val checkTime: LocalDateTime?,
    val result: DryResult?,
    /** Only when the user says when it was dry. */
    val actualFinish: LocalDateTime?,
    /** Hourly weather from the hang hour on. */
    val weather: List<HourlyWeather>,
    val weatherSource: WeatherSource,
)
