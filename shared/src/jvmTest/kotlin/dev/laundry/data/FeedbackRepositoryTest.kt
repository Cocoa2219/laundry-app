package dev.laundry.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.laundry.drying.Crowding
import dev.laundry.drying.DAY
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Spot
import dev.laundry.drying.at
import dev.laundry.drying.forecast
import dev.laundry.drying.simulate
import dev.laundry.weather.CachedForecast
import dev.laundry.weather.ForecastCache
import dev.laundry.weather.GeoPoint
import dev.laundry.weather.OpenMeteoClient
import dev.laundry.weather.WeatherRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedbackRepositoryTest {
    private val towel = Load(Fabric.TOWEL, Crowding.NORMAL, Spot.OPEN_YARD)
    private val seoul = GeoPoint(37.57, 126.98)

    private val database = Room.inMemoryDatabaseBuilder<LaundryDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    @AfterTest
    fun closeDatabase() = database.close()

    private object NoCache : ForecastCache {
        override suspend fun read(): CachedForecast? = null
        override suspend fun write(entry: CachedForecast) = Unit
    }

    /** Requests seen by the fake weather server, as their past_days value. */
    private val pastDaysRequested = mutableListOf<String?>()

    private fun repository(online: Boolean = true): FeedbackRepository {
        val engine = MockEngine { request ->
            pastDaysRequested += request.url.parameters["past_days"]
            if (!online) throw RuntimeException("offline")
            respond(observedWeatherJson(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return FeedbackRepository(database, WeatherRepository(OpenMeteoClient(HttpClient(engine)), NoCache))
    }

    @Test
    fun recordHang_storesPhysicsPredictionAndForecastFromHangHour() = runTest {
        val repo = repository()
        val forecast = forecast()

        val id = repo.recordHang(towel, seoul, at(9, 30), forecast)
        val record = assertNotNull(repo.latestUnanswered(now = at(10)))

        assertEquals(id, record.id)
        assertEquals(towel, record.load)
        assertEquals(seoul, record.point)
        assertEquals(simulate(forecast, towel, at(9, 30)).finish, record.predictedFinish)
        assertEquals(at(9), record.weather.first().time, "starts at the hour the load was hung in")
        assertEquals(WeatherSource.FORECAST, record.weatherSource)
        assertNull(record.result)
    }

    @Test
    fun recordHang_predictionIsNullWhenPhysicsSaysItWontDry() = runTest {
        val repo = repository()
        val bedding = Load(Fabric.BEDDING, Crowding.CROWDED, Spot.OPEN_YARD)

        repo.recordHang(bedding, seoul, at(16), forecast())

        assertNull(repo.latestUnanswered(now = at(17))!!.predictedFinish)
    }

    @Test
    fun latestUnanswered_onlyWithin24Hours_andNewestFirst() = runTest {
        val repo = repository()
        repo.recordHang(towel, seoul, at(8), forecast())
        val newer = repo.recordHang(towel, seoul, at(9), forecast())

        assertEquals(newer, repo.latestUnanswered(now = at(12))?.id)
        assertNull(repo.latestUnanswered(now = at(10, date = LocalDate(2026, 7, 2))))
    }

    @Test
    fun deleteHang_removesIt() = runTest {
        val repo = repository()
        val id = repo.recordHang(towel, seoul, at(9), forecast())

        repo.deleteHang(id)

        assertNull(repo.latestUnanswered(now = at(10)))
    }

    @Test
    fun answerDry_refetchesActualWeatherForTheDryingPeriod() = runTest {
        val repo = repository()
        val id = repo.recordHang(towel, seoul, at(9, 30), forecast())

        repo.answer(id, DryResult.DRY, checkTime = at(14), actualFinish = at(13, 15))
        val record = repo.answered().single()

        assertEquals(listOf("0"), pastDaysRequested.map { it ?: "0" }, "hung today: no extra past days")
        assertEquals(WeatherSource.PAST_DAYS, record.weatherSource)
        assertEquals((9..13).map { at(it) }, record.weather.map { it.time })
        assertEquals(40.0, record.weather.first().rhPercent, "observed values replaced the forecast")
        assertEquals(DryResult.DRY, record.result)
        assertEquals(at(14), record.checkTime)
        assertEquals(at(13, 15), record.actualFinish)
        assertNull(repo.latestUnanswered(now = at(15)))
    }

    @Test
    fun answerNextDay_asksForOnePastDay() = runTest {
        val repo = repository()
        val id = repo.recordHang(towel, seoul, at(16), forecast())

        repo.answer(id, DryResult.STILL_DAMP, checkTime = at(8, date = LocalDate(2026, 7, 2)), actualFinish = null)

        assertEquals(listOf<String?>("1"), pastDaysRequested)
    }

    @Test
    fun answerOffline_keepsForecastSnapshot() = runTest {
        val repo = repository(online = false)
        val id = repo.recordHang(towel, seoul, at(9), forecast())

        repo.answer(id, DryResult.DRY, checkTime = at(14), actualFinish = null)
        val record = repo.answered().single()

        assertEquals(DryResult.DRY, record.result)
        assertEquals(WeatherSource.FORECAST, record.weatherSource)
        assertEquals(50.0, record.weather.first().rhPercent, "forecast values kept")
    }

    @Test
    fun answerSkipped_doesNotRefetch() = runTest {
        val repo = repository()
        val id = repo.recordHang(towel, seoul, at(9), forecast())

        repo.answer(id, DryResult.SKIPPED, checkTime = at(11), actualFinish = null)

        assertEquals(emptyList<String?>(), pastDaysRequested)
        assertEquals(DryResult.SKIPPED, repo.answered().single().result)
    }

    /** "Observed" weather for [DAY]: 40 % humidity, so it differs from the 50 % test forecast. */
    private fun observedWeatherJson(): String {
        val times = (0 until 24).joinToString(",") { "\"${DAY}T%02d:00\"".format(it) }
        fun repeat(value: String) = List(24) { value }.joinToString(",")
        return """
            {"timezone":"Asia/Seoul",
             "hourly":{"time":[$times],
               "temperature_2m":[${repeat("26.0")}],"relative_humidity_2m":[${repeat("40")}],
               "wind_speed_10m":[${repeat("2.0")}],"shortwave_radiation":[${repeat("300.0")}],
               "precipitation_probability":[${repeat("0")}],"precipitation":[${repeat("0.0")}]},
             "daily":{"time":["$DAY"],"sunrise":["${DAY}T05:11"],"sunset":["${DAY}T19:57"]}}
        """.trimIndent()
    }
}
