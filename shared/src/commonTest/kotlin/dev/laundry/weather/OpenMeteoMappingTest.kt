package dev.laundry.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OpenMeteoMappingTest {
    private val fetchedAt = Instant.fromEpochSeconds(1_793_190_000)

    private fun parse(json: String) =
        openMeteoJson.decodeFromString<ForecastDto>(json).toForecast(fetchedAt, fromCache = false)

    @Test
    fun multiModel_prefersMsmAndFillsGapsFromBestMatch() {
        val hours = parse(MULTI_MODEL_JSON).hourly

        assertEquals(3, hours.size)
        assertEquals(
            HourlyWeather(
                time = LocalDateTime.parse("2026-10-28T10:00"),
                tempC = 10.0,
                rhPercent = 50.0,
                windMs = 2.0,
                radiationWm2 = 400.0,
                rainProb = 10,
                rainMm = 0.0,
            ),
            hours[0],
        )
        assertEquals(11.0, hours[1].tempC, "MSM temperature missing, best_match fills it")
        assertEquals(60, hours[1].rainProb, "rain probability always comes from best_match")
        assertEquals(0.2, hours[1].rainMm, "MSM precipitation wins when present")
        assertEquals(3.0, hours[2].windMs, "MSM wind missing, best_match fills it")
    }

    @Test
    fun multiModel_readsSuffixedSunTimes() {
        val sun = parse(MULTI_MODEL_JSON).sun.single()

        assertEquals(LocalDate.parse("2026-10-28"), sun.date)
        assertEquals(LocalDateTime.parse("2026-10-28T06:52"), sun.sunrise)
        assertEquals(LocalDateTime.parse("2026-10-28T17:38"), sun.sunset)
    }

    @Test
    fun singleModel_readsPlainKeys() {
        val forecast = parse(SINGLE_MODEL_JSON)

        assertEquals("Asia/Singapore", forecast.timezone)
        assertEquals(LocalDateTime.parse("2026-10-28T19:01"), forecast.sun.single().sunset)
        assertEquals(27.0, forecast.hourly.first().tempC)
    }

    @Test
    fun hourMissingADryingInput_isDropped() {
        val times = parse(SINGLE_MODEL_JSON).hourly.map { it.time.hour }

        assertEquals(listOf(0, 2), times)
    }

    @Test
    fun missingRainProbability_countsAsZero() {
        val last = parse(SINGLE_MODEL_JSON).hourly.last()

        assertEquals(0, last.rainProb)
        assertEquals(0.5, last.rainMm)
    }
}
