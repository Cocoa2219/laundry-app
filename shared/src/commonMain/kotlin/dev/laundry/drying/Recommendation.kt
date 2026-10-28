package dev.laundry.drying

import dev.laundry.weather.Forecast
import dev.laundry.weather.HourlyWeather
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** How one candidate start time fares. */
enum class StartVerdict {
    /** Dry at least an hour before the first cutoff. */
    GO,

    /** Dry, but within an hour of the first cutoff: a forecast slip could leave it damp. */
    RISKY,

    /** Not dry before the first cutoff. */
    FAILS,
}

data class StartOption(val result: SimulationResult, val verdict: StartVerdict) {
    val start: LocalDateTime get() = result.start
}

enum class Verdict { HANG_OUT, RISKY, INDOOR, DRYER }

/** Average weather over the drying period, for the "why?" breakdown. */
data class Conditions(
    val avgRhPercent: Double,
    val avgWindMs: Double,
    val avgRadiationWm2: Double,
)

data class Why(
    /** Null when the forecast has no hours for the period. */
    val conditions: Conditions?,
    /** What limits drying today; null indoors. */
    val cutoff: Cutoff?,
)

/**
 * Spread of the finish time under plausible forecast errors.
 * [latest] is null when a pessimistic forecast would not dry before the cutoff.
 */
data class FinishRange(val earliest: LocalDateTime, val latest: LocalDateTime?)

data class Recommendation(
    val verdict: Verdict,
    /** Latest start that still works outdoors; null for INDOOR and DRYER. */
    val hangBy: LocalDateTime?,
    /** Predicted finish for the earliest workable start (or for drying indoors from now). */
    val inBy: LocalDateTime?,
    /** Null indoors, where the forecast does not matter. */
    val range: FinishRange?,
    val why: Why,
    /** Every outdoor start considered, in time order; empty when [Load.spot] is INDOOR. */
    val options: List<StartOption>,
)

// Initial estimates, like the physics constants.
private const val SAFETY_MARGIN_MINUTES = 60.0
private const val LATEST_START_HOUR = 14
private const val LATEST_START_BEFORE_SUNSET_MINUTES = 180.0
private const val INDOOR_MAX_HOURS = 12.0

/**
 * Recommends when to hang [load] given the time [now] (local time of the forecast).
 * [corrections] holds the learned rate multiplier per spot; a missing spot means physics only.
 */
fun recommend(
    forecast: Forecast,
    load: Load,
    now: LocalDateTime,
    corrections: Map<Spot, Double> = emptyMap(),
): Recommendation {
    val indoorCorrection = corrections[Spot.INDOOR] ?: 1.0
    if (load.spot == Spot.INDOOR) {
        return dryIndoors(forecast, load, now, indoorCorrection, options = emptyList(), why = INDOOR_WHY)
    }

    val correction = corrections[load.spot] ?: 1.0
    val options = candidateStarts(forecast, now).map { start ->
        val result = simulate(forecast, load, start, correction)
        StartOption(result, verdictFor(result))
    }
    val earliestViable = options.firstOrNull { it.verdict != StartVerdict.FAILS }
    val why = whyFor(forecast, load.spot, now, basis = earliestViable ?: options.firstOrNull())

    if (earliestViable == null) {
        return dryIndoors(forecast, load, now, indoorCorrection, options, why)
    }
    val latestGo = options.lastOrNull { it.verdict == StartVerdict.GO }
    val latestRisky = options.lastOrNull { it.verdict == StartVerdict.RISKY }
    return Recommendation(
        verdict = if (latestGo != null) Verdict.HANG_OUT else Verdict.RISKY,
        hangBy = (latestGo ?: latestRisky)?.start,
        inBy = earliestViable.result.finish,
        range = finishRange(forecast, load, earliestViable.start, correction),
        why = why,
        options = options,
    )
}

private val INDOOR_WHY = Why(Conditions(INDOOR_RH_PERCENT, 0.0, 0.0), cutoff = null)

/** INDOOR when drying inside from [now] takes at most 12 hours, otherwise DRYER. */
private fun dryIndoors(
    forecast: Forecast,
    load: Load,
    now: LocalDateTime,
    indoorCorrection: Double,
    options: List<StartOption>,
    why: Why,
): Recommendation {
    val indoor = simulate(forecast, load.copy(spot = Spot.INDOOR), now, indoorCorrection)
    val finish = indoor.finish!! // indoors always finishes: no cutoffs, constant positive rate
    val fastEnough = minutesBetween(now, finish) <= INDOOR_MAX_HOURS * 60
    return Recommendation(
        verdict = if (fastEnough) Verdict.INDOOR else Verdict.DRYER,
        hangBy = null,
        inBy = if (fastEnough) finish else null,
        range = null,
        why = why,
        options = options,
    )
}

/**
 * Full hours from the next hour through min(14:00, sunset − 3 h). Later starts rarely finish
 * before dark, and recommending them would push people to hang laundry in the evening.
 */
internal fun candidateStarts(forecast: Forecast, now: LocalDateTime): List<LocalDateTime> {
    val fixedLimit = LocalDateTime(now.date, LocalTime(LATEST_START_HOUR, 0))
    val sunsetLimit = forecast.sun.firstOrNull { it.date == now.date }
        ?.sunset?.plusMinutes(-LATEST_START_BEFORE_SUNSET_MINUTES)
    val limit = if (sunsetLimit != null && sunsetLimit < fixedLimit) sunsetLimit else fixedLimit

    return generateSequence(now.truncatedToHour().plusMinutes(60.0)) { it.plusMinutes(60.0) }
        .takeWhile { it <= limit }
        .toList()
}

internal fun verdictFor(result: SimulationResult): StartVerdict {
    val finish = result.finish ?: return StartVerdict.FAILS
    val cutoff = result.cutoff ?: return StartVerdict.GO
    return if (minutesBetween(finish, cutoff.time) >= SAFETY_MARGIN_MINUTES) StartVerdict.GO else StartVerdict.RISKY
}

/**
 * Reruns the simulation with each plausible forecast error (humidity ±10 points, wind ×0.7
 * and ×1.3) and takes the earliest and latest finish, including the unchanged forecast.
 */
internal fun finishRange(forecast: Forecast, load: Load, start: LocalDateTime, correction: Double): FinishRange {
    val variants = listOf<(HourlyWeather) -> HourlyWeather>(
        { it.copy(rhPercent = (it.rhPercent - 10).coerceIn(0.0, 100.0)) },
        { it.copy(rhPercent = (it.rhPercent + 10).coerceIn(0.0, 100.0)) },
        { it.copy(windMs = it.windMs * 0.7) },
        { it.copy(windMs = it.windMs * 1.3) },
    )
    val finishes = listOf(simulate(forecast, load, start, correction).finish) +
        variants.map { perturb -> simulate(forecast.copy(hourly = forecast.hourly.map(perturb)), load, start, correction).finish }

    val dried = finishes.filterNotNull()
    return FinishRange(
        earliest = dried.min(),
        latest = if (dried.size == finishes.size) dried.max() else null,
    )
}

/** Averages over [basis]'s drying period; with no basis, over the next hour from [now]. */
private fun whyFor(forecast: Forecast, spot: Spot, now: LocalDateTime, basis: StartOption?): Why {
    val from = basis?.start ?: now
    val end = basis?.result?.let { it.finish ?: it.cutoff?.time }
    val to = if (end != null && end > from) end else from.plusMinutes(60.0)
    return Why(
        conditions = averageConditions(forecast, from, to),
        cutoff = basis?.result?.cutoff ?: firstCutoff(forecast, spot, now),
    )
}

/** Plain average of the forecast hours that overlap [from, to). */
internal fun averageConditions(forecast: Forecast, from: LocalDateTime, to: LocalDateTime): Conditions? {
    val hours = forecast.hourly.filter { it.time < to && it.time.plusMinutes(60.0) > from }
    if (hours.isEmpty()) return null
    return Conditions(
        avgRhPercent = hours.map { it.rhPercent }.average(),
        avgWindMs = hours.map { it.windMs }.average(),
        avgRadiationWm2 = hours.map { it.radiationWm2 }.average(),
    )
}
