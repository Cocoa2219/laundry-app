package dev.laundry.drying

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationTest {
    private val towelInYard = Load(Fabric.TOWEL, Crowding.NORMAL, Spot.OPEN_YARD)
    private val towelOnBalcony = towelInYard.copy(spot = Spot.BALCONY)
    private val towelIndoors = towelInYard.copy(spot = Spot.INDOOR)

    // Scenarios from CLAUDE.md "Tests".

    @Test
    fun mildCloudyDay_towelDriesInThreeToFiveHours() {
        val result = simulate(forecast(weather(25.0, 50.0, 2.0, radiationWm2 = 100.0)), towelInYard, at(9))

        assertTrue(result.hours() in 3.0..5.0, "took ${result.hours()} h")
    }

    @Test
    fun hotHumidStillDay_towelDoesNotFinishBeforeNight() {
        val muggy = forecast(weather(30.0, 85.0, 0.0, radiationWm2 = 400.0), sunsetHour = 18)

        val result = simulate(muggy, towelInYard, at(9))

        assertFalse(result.dries)
        assertEquals(Cutoff(at(19), CutoffReason.NIGHT), result.cutoff)
    }

    @Test
    fun coldDryWindySunnyDay_isFasterThanMildCloudyDay() {
        val mild = simulate(forecast(weather(25.0, 50.0, 2.0, radiationWm2 = 100.0)), towelInYard, at(9))
        val crisp = simulate(forecast(weather(12.0, 35.0, 5.0, radiationWm2 = 600.0)), towelInYard, at(9))

        assertTrue(crisp.hours() < mild.hours(), "crisp ${crisp.hours()} h vs mild ${mild.hours()} h")
    }

    @Test
    fun rainAt15_openYard_cutsOffAt15() {
        val rainy = forecast(overrides = mapOf(15 to weather(rainProb = 80)))

        val result = simulate(rainy, towelInYard, at(9))

        assertEquals(Cutoff(at(15), CutoffReason.RAIN), result.cutoff)
        assertTrue(result.finish!! < at(15))
    }

    @Test
    fun rainAt15_openYard_slowLoadDoesNotFinish() {
        val rainy = forecast(overrides = mapOf(15 to weather(rainProb = 80)))
        val bedding = Load(Fabric.BEDDING, Crowding.CROWDED, Spot.OPEN_YARD)

        val result = simulate(rainy, bedding, at(9))

        assertFalse(result.dries)
        assertEquals(CutoffReason.RAIN, result.cutoff?.reason)
    }

    @Test
    fun rainAt15_balcony_rainIsIgnored() {
        val rainy = forecast(overrides = mapOf(15 to weather(rainProb = 80, rainMm = 3.0)))

        val result = simulate(rainy, towelOnBalcony, at(9))

        assertEquals(Cutoff(at(20), CutoffReason.NIGHT), result.cutoff)
    }

    @Test
    fun indoor_usesFixedConditionsAndIgnoresNightAndRain() {
        val stormy = forecast(weather(5.0, 99.0, 15.0, 0.0, rainProb = 100, rainMm = 20.0), sunsetHour = 17)
        val perfect = forecast(weather(30.0, 20.0, 5.0, 800.0))

        val inStorm = simulate(stormy, towelIndoors, at(9))
        val inSun = simulate(perfect, towelIndoors, at(9))

        assertEquals(inSun, inStorm)
        assertNull(inStorm.cutoff)
        assertEquals(towelIndoors.threshold / INDOOR_RATE, inStorm.hours(), 1.0 / 60)
    }

    // Edge cases.

    @Test
    fun rainThresholds_probability50OrMoreThan0point1mm() {
        fun cutoffWith(rain: Map<Int, Pair<Int, Double>>) =
            simulate(
                forecast(overrides = rain.mapValues { (_, r) -> weather(rainProb = r.first, rainMm = r.second) }),
                Load(Fabric.BEDDING, Crowding.CROWDED, Spot.OPEN_YARD),
                at(9),
            ).cutoff?.reason

        assertEquals(CutoffReason.NIGHT, cutoffWith(mapOf(12 to (49 to 0.1))))
        assertEquals(CutoffReason.RAIN, cutoffWith(mapOf(12 to (50 to 0.0))))
        assertEquals(CutoffReason.RAIN, cutoffWith(mapOf(12 to (0 to 0.2))))
    }

    @Test
    fun rainAlreadyFalling_atStart_cannotDry() {
        val result = simulate(forecast(overrides = mapOf(9 to weather(rainProb = 90))), towelInYard, at(9, 30))

        assertFalse(result.dries)
        assertEquals(Cutoff(at(9), CutoffReason.RAIN), result.cutoff)
    }

    @Test
    fun rainBeforeStart_isIgnored() {
        val result = simulate(forecast(overrides = mapOf(7 to weather(rainProb = 90))), towelInYard, at(9))

        assertEquals(CutoffReason.NIGHT, result.cutoff?.reason)
    }

    @Test
    fun startAfterNightCutoff_cannotDry() {
        val result = simulate(forecast(sunsetHour = 19), towelInYard, at(21))

        assertFalse(result.dries)
    }

    @Test
    fun constantWeather_finishIsExactlyThresholdOverRate() {
        val w = weather()
        val expectedHours = towelInYard.threshold / dryingRate(w, Spot.OPEN_YARD)

        val result = simulate(forecast(w), towelInYard, at(9))

        assertEquals(expectedHours, result.hours(), 1.0 / 60)
    }

    @Test
    fun partialStartHour_isProrated() {
        val fromFull = simulate(forecast(), towelInYard, at(9))
        val fromHalf = simulate(forecast(), towelInYard, at(9, 30))

        assertEquals(fromFull.hours(), fromHalf.hours(), 1.0 / 60)
    }

    @Test
    fun ratesChangeBetweenHours_eachHourUsesItsOwnWeather() {
        // 09:00 removes ~10 units, every other hour is saturated: enough for a thin top (5)
        // but not a towel (12.65).
        val oneGoodHour = forecast(
            base = weather(rhPercent = 100.0),
            overrides = mapOf(9 to weather(30.0, 20.0, 5.0, 300.0)),
        )
        val thin = Load(Fabric.THIN_TOP, Crowding.SPACED, Spot.OPEN_YARD)

        assertTrue(simulate(oneGoodHour, thin, at(9)).dries)
        assertFalse(simulate(oneGoodHour, towelInYard, at(9)).dries)
    }

    @Test
    fun correction_scalesDryingTime() {
        val physics = simulate(forecast(), towelInYard, at(9))
        val twiceAsFast = simulate(forecast(), towelInYard, at(9), correction = 2.0)

        assertEquals(physics.hours() / 2, twiceAsFast.hours(), 1.0 / 60)
    }

    @Test
    fun missingHour_stopsWithNoData() {
        val gappy = forecast(missingHours = setOf(11))

        val result = simulate(gappy, Load(Fabric.BEDDING, Crowding.CROWDED, Spot.OPEN_YARD), at(9))

        assertFalse(result.dries)
        assertEquals(Cutoff(at(11), CutoffReason.NO_DATA), result.cutoff)
    }
}
