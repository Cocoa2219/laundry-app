package dev.laundry.drying

import dev.laundry.weather.HourlyWeather
import kotlin.math.exp
import kotlin.math.sqrt

// Indoor drying ignores the forecast: a typical heated room with still air and no sun.
internal const val INDOOR_TEMP_C = 22.0
internal const val INDOOR_RH_PERCENT = 55.0

/** Tetens equation: saturation vapour pressure over water, in kPa. */
internal fun saturationVaporPressureKpa(tempC: Double): Double =
    0.6108 * exp(17.27 * tempC / (tempC + 237.3))

/**
 * Drying units removed per hour by air at the given conditions.
 *
 * Vapour pressure deficit is the air's capacity to take up more water. Wind and sun each
 * multiply it: wind with diminishing returns (square root), sun linearly, where 600 W/m²
 * (a bright midday) doubles the rate.
 */
internal fun airDryingRate(
    tempC: Double,
    rhPercent: Double,
    effectiveWindMs: Double,
    effectiveRadiationWm2: Double,
): Double {
    val vpd = saturationVaporPressureKpa(tempC) * (1 - rhPercent.coerceIn(0.0, 100.0) / 100)
    val fWind = 1 + 0.5 * sqrt(effectiveWindMs.coerceAtLeast(0.0))
    val fSun = 1 + effectiveRadiationWm2.coerceAtLeast(0.0) / 600
    return vpd * fWind * fSun
}

internal val INDOOR_RATE: Double = airDryingRate(INDOOR_TEMP_C, INDOOR_RH_PERCENT, 0.0, 0.0)

/** Physics-only drying rate for one forecast hour at [spot]. */
fun dryingRate(weather: HourlyWeather, spot: Spot): Double =
    if (spot == Spot.INDOOR) {
        INDOOR_RATE
    } else {
        airDryingRate(
            tempC = weather.tempC,
            rhPercent = weather.rhPercent,
            effectiveWindMs = weather.windMs * spot.windFactor,
            effectiveRadiationWm2 = weather.radiationWm2 * spot.sunFactor,
        )
    }
