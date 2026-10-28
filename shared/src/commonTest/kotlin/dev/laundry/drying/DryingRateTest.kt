package dev.laundry.drying

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DryingRateTest {

    @Test
    fun saturationVaporPressure_matchesTetensReferenceValues() {
        assertEquals(0.6108, saturationVaporPressureKpa(0.0), 1e-4)
        assertEquals(3.168, saturationVaporPressureKpa(25.0), 1e-3)
        assertEquals(4.243, saturationVaporPressureKpa(30.0), 1e-3)
    }

    @Test
    fun openYard_rateFollowsFormula() {
        // VPD 1.584 · fWind (1 + 0.5·√(2·0.8)) · fSun (1 + 100/600)
        val rate = dryingRate(weather(tempC = 25.0, rhPercent = 50.0, windMs = 2.0, radiationWm2 = 100.0), Spot.OPEN_YARD)

        assertEquals(1.584 * 1.6325 * 1.1667, rate, 1e-2)
    }

    @Test
    fun balcony_getsLessWindAndSunThanOpenYard() {
        val w = weather(windMs = 4.0, radiationWm2 = 600.0)

        assertTrue(dryingRate(w, Spot.BALCONY) < dryingRate(w, Spot.OPEN_YARD))
    }

    @Test
    fun saturatedAir_doesNotDry() {
        assertEquals(0.0, dryingRate(weather(rhPercent = 100.0), Spot.OPEN_YARD))
    }

    @Test
    fun indoor_usesFixedConditionsWhateverTheWeather() {
        val expected = saturationVaporPressureKpa(22.0) * (1 - 0.55)

        assertEquals(expected, dryingRate(weather(tempC = 35.0, rhPercent = 20.0, windMs = 9.0), Spot.INDOOR), 1e-9)
        assertEquals(expected, dryingRate(weather(tempC = -5.0, rhPercent = 95.0, radiationWm2 = 0.0), Spot.INDOOR), 1e-9)
    }

    @Test
    fun threshold_isUnitsTimesCrowding() {
        assertEquals(11.0 * 1.15, Load(Fabric.TOWEL, Crowding.NORMAL, Spot.OPEN_YARD).threshold, 1e-9)
        assertEquals(22.0 * 1.3, Load(Fabric.BEDDING, Crowding.CROWDED, Spot.INDOOR).threshold, 1e-9)
    }
}
