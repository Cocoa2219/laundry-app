package dev.laundry.drying

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationTest {
    private val towelInYard = Load(Fabric.TOWEL, Crowding.NORMAL, Spot.OPEN_YARD)
    private val beddingInYard = Load(Fabric.BEDDING, Crowding.CROWDED, Spot.OPEN_YARD)
    private val morning = at(7, 30)

    // Candidate start times.

    @Test
    fun candidates_runFromNextHourTo14() {
        val starts = candidateStarts(forecast(sunsetHour = 19), morning)

        assertEquals((8..14).map { at(it) }, starts)
    }

    @Test
    fun candidates_stopThreeHoursBeforeAnEarlySunset() {
        // Sunset 16:00 → last start 13:00.
        val starts = candidateStarts(forecast(sunsetHour = 16), morning)

        assertEquals(at(13), starts.last())
    }

    @Test
    fun candidates_noneAfter14() {
        assertTrue(candidateStarts(forecast(), at(14, 10)).isEmpty())
    }

    // Scenarios from CLAUDE.md "Tests" (verdict half).

    @Test
    fun mildCloudyDay_hangOutWithEarliestFinishAsInBy() {
        // Towel takes 4.19 h; night cutoff 20:00, so every start up to 14:00 is GO.
        val rec = recommend(forecast(), towelInYard, morning)

        assertEquals(Verdict.HANG_OUT, rec.verdict)
        assertEquals(at(14), rec.hangBy)
        assertEquals(at(12, 12), rec.inBy)
    }

    @Test
    fun hotHumidStillDay_isRiskyOrWorse() {
        val muggy = forecast(weather(30.0, 85.0, 0.0, radiationWm2 = 400.0), sunsetHour = 18)

        val rec = recommend(muggy, towelInYard, morning)

        assertTrue(rec.verdict != Verdict.HANG_OUT, "was ${rec.verdict}")
    }

    @Test
    fun rainAt15_openYard_inByBefore14AndLastGoStartIs9() {
        // 09:00 → 13:12 (GO); 10:00 → 14:12, only 48 min before the rain (RISKY).
        val rainy = forecast(overrides = mapOf(15 to weather(rainProb = 80)))

        val rec = recommend(rainy, towelInYard, morning)

        assertEquals(Verdict.HANG_OUT, rec.verdict)
        assertEquals(at(9), rec.hangBy)
        assertTrue(rec.inBy!! <= at(14), "in by ${rec.inBy}")
        assertEquals(CutoffReason.RAIN, rec.why.cutoff?.reason)
    }

    @Test
    fun rainAt15_openYard_loadThatCannotFinish_isNotHangOut() {
        val rainy = forecast(overrides = mapOf(15 to weather(rainProb = 80)))

        val rec = recommend(rainy, beddingInYard, morning)

        assertNotEquals(Verdict.HANG_OUT, rec.verdict)
        assertNull(rec.hangBy)
    }

    @Test
    fun rainAt15_balcony_rainIgnored() {
        // Balcony towel takes 4.77 h: a 14:00 start ends 18:46, over an hour before 20:00.
        val rainy = forecast(overrides = mapOf(15 to weather(rainProb = 80, rainMm = 3.0)))

        val rec = recommend(rainy, towelInYard.copy(spot = Spot.BALCONY), morning)

        assertEquals(Verdict.HANG_OUT, rec.verdict)
        assertEquals(at(14), rec.hangBy)
        assertEquals(CutoffReason.NIGHT, rec.why.cutoff?.reason)
    }

    // Overall verdicts.

    @Test
    fun onlyRiskyStarts_verdictIsRisky() {
        // 5.44 h towel, night cutoff 14:00: 08:00 ends 13:26 (34 min margin), 09:00 misses.
        val tight = forecast(weather(rhPercent = 55.0, radiationWm2 = 0.0), sunsetHour = 13)

        val rec = recommend(tight, towelInYard, morning)

        assertEquals(Verdict.RISKY, rec.verdict)
        assertEquals(at(8), rec.hangBy)
        assertEquals(at(13, 26), rec.inBy)
        assertEquals(listOf(StartVerdict.RISKY, StartVerdict.FAILS, StartVerdict.FAILS), rec.options.map { it.verdict })
    }

    @Test
    fun outdoorFails_indoorWithin12h_verdictIndoor() {
        // Towel indoors: 10.6 h.
        val rec = recommend(forecast(weather(rhPercent = 100.0)), towelInYard, morning)

        assertEquals(Verdict.INDOOR, rec.verdict)
        assertNull(rec.hangBy)
        assertEquals(at(18, 8), rec.inBy)
    }

    @Test
    fun outdoorFails_indoorOver12h_verdictDryer() {
        // Crowded bedding indoors: 24 h.
        val rec = recommend(forecast(weather(rhPercent = 100.0)), beddingInYard, morning)

        assertEquals(Verdict.DRYER, rec.verdict)
        assertNull(rec.inBy)
    }

    @Test
    fun tooLateForOutdoorStarts_fallsBackToIndoor() {
        val rec = recommend(forecast(), towelInYard, at(15))

        assertTrue(rec.options.isEmpty())
        assertEquals(Verdict.INDOOR, rec.verdict)
    }

    @Test
    fun indoorSpot_skipsOutdoorStartsAndUsesFixedConditions() {
        val towelIndoors = towelInYard.copy(spot = Spot.INDOOR)

        val rec = recommend(forecast(), towelIndoors, morning)

        assertEquals(Verdict.INDOOR, rec.verdict)
        assertTrue(rec.options.isEmpty())
        assertEquals(at(18, 8), rec.inBy)
        assertEquals(Why(Conditions(55.0, 0.0, 0.0), cutoff = null), rec.why)
        assertNull(rec.range)
    }

    @Test
    fun indoorSpot_goodOutdoorDay_suggestsTheYard() {
        val rec = recommend(forecast(), towelInYard.copy(spot = Spot.INDOOR), morning)

        assertEquals(OutdoorAlternative(Spot.OPEN_YARD, hangBy = at(14), inBy = at(12, 12)), rec.outdoorAlternative)
    }

    @Test
    fun indoorSpot_riskyOrBadOutdoorDay_suggestsNothing() {
        val tight = forecast(weather(rhPercent = 55.0, radiationWm2 = 0.0), sunsetHour = 13)
        val saturated = forecast(weather(rhPercent = 100.0))
        val towelIndoors = towelInYard.copy(spot = Spot.INDOOR)

        assertNull(recommend(tight, towelIndoors, morning).outdoorAlternative)
        assertNull(recommend(saturated, towelIndoors, morning).outdoorAlternative)
    }

    @Test
    fun outdoorSpot_neverCarriesAnAlternative() {
        assertNull(recommend(forecast(), towelInYard, morning).outdoorAlternative)
    }

    @Test
    fun indoorSpot_slowLoad_dryer() {
        assertEquals(Verdict.DRYER, recommend(forecast(), beddingInYard.copy(spot = Spot.INDOOR), morning).verdict)
    }

    // Confidence range, why, corrections.

    @Test
    fun range_bracketsInBy() {
        val rec = recommend(forecast(), towelInYard, morning)
        val range = assertNotNull(rec.range)
        val inBy = assertNotNull(rec.inBy)

        assertTrue(range.earliest < inBy)
        assertTrue(assertNotNull(range.latest) > inBy)
    }

    @Test
    fun range_latestNullWhenPessimisticForecastMissesCutoff() {
        // Humidity +10 slows the 08:00 start to 6.99 h, past the 14:00 night cutoff.
        val tight = forecast(weather(rhPercent = 55.0, radiationWm2 = 0.0), sunsetHour = 13)

        val range = assertNotNull(recommend(tight, towelInYard, morning).range)

        assertNull(range.latest)
    }

    @Test
    fun why_averagesTheDryingPeriod() {
        val rec = recommend(forecast(weather(rhPercent = 40.0, windMs = 3.0, radiationWm2 = 250.0)), towelInYard, morning)

        assertEquals(Conditions(40.0, 3.0, 250.0), rec.why.conditions)
        assertEquals(CutoffReason.NIGHT, rec.why.cutoff?.reason)
    }

    @Test
    fun correction_isAppliedPerSpot() {
        val physics = recommend(forecast(), towelInYard, morning)
        val learnedFast = recommend(forecast(), towelInYard, morning, corrections = mapOf(Spot.OPEN_YARD to 2.0))
        val otherSpotOnly = recommend(forecast(), towelInYard, morning, corrections = mapOf(Spot.BALCONY to 2.0))

        assertTrue(learnedFast.inBy!! < physics.inBy!!)
        assertEquals(physics, otherSpotOnly)
    }
}
