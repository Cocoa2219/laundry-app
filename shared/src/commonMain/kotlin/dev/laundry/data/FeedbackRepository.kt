package dev.laundry.data

import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Spot
import dev.laundry.drying.plusMinutes
import dev.laundry.drying.simulate
import dev.laundry.weather.Forecast
import dev.laundry.weather.GeoPoint
import dev.laundry.weather.HourlyWeather
import dev.laundry.weather.WeatherRepository
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The "I hung it" → "Did it dry?" flow, as the UI sees it. */
interface FeedbackLog {
    suspend fun recordHang(load: Load, point: GeoPoint, hangTime: LocalDateTime, forecast: Forecast): Long

    suspend fun deleteHang(id: Long)

    suspend fun latestUnanswered(now: LocalDateTime): FeedbackRecord?

    suspend fun answer(id: Long, result: DryResult, checkTime: LocalDateTime, actualFinish: LocalDateTime?)
}

/** Hangs the user recorded and their feedback, for the "Did it dry?" flow and for learning. */
class FeedbackRepository internal constructor(
    private val dao: HangDao,
    private val weather: WeatherRepository,
) : FeedbackLog {
    constructor(database: LaundryDatabase, weather: WeatherRepository) : this(database.hangDao(), weather)

    /**
     * Records that [load] was hung at [hangTime] at [point]. Stores the physics-only prediction
     * and the forecast hours from the hang hour on, so the record is complete even if the
     * weather can't be re-fetched later.
     */
    override suspend fun recordHang(load: Load, point: GeoPoint, hangTime: LocalDateTime, forecast: Forecast): Long {
        val hours = forecast.hourly.filter { it.time.plusMinutes(60.0) > hangTime }
        return dao.insert(
            HangEntity(
                fabric = load.fabric.name,
                crowding = load.crowding.name,
                spot = load.spot.name,
                latitude = point.latitude,
                longitude = point.longitude,
                hangTime = hangTime.toString(),
                predictedFinish = simulate(forecast, load, hangTime, correction = 1.0).finish?.toString(),
                checkTime = null,
                result = null,
                actualFinish = null,
                weatherJson = encodeWeather(hours),
                weatherSource = WeatherSource.FORECAST.name,
            ),
        )
    }

    override suspend fun deleteHang(id: Long) = dao.delete(id)

    /** The most recent hang without an answer, if it was hung within the last 24 hours of [now]. */
    override suspend fun latestUnanswered(now: LocalDateTime): FeedbackRecord? =
        dao.latestUnanswered(since = now.plusMinutes(-24 * 60.0).toString())?.toRecord()

    /**
     * Saves the answer. For DRY and STILL_DAMP the weather is re-fetched with `past_days` so the
     * record holds what actually happened; on failure the forecast snapshot is kept.
     * SKIPPED records are never used for training, so they are not re-fetched.
     */
    override suspend fun answer(id: Long, result: DryResult, checkTime: LocalDateTime, actualFinish: LocalDateTime?) {
        val hang = dao.get(id) ?: return
        val answered = hang.copy(result = result.name, checkTime = checkTime.toString(), actualFinish = actualFinish?.toString())
        if (result == DryResult.SKIPPED) {
            dao.update(answered)
            return
        }
        val actualHours = pastWeather(hang, checkTime)
        dao.update(
            if (actualHours.isNullOrEmpty()) {
                answered
            } else {
                answered.copy(weatherJson = encodeWeather(actualHours), weatherSource = WeatherSource.PAST_DAYS.name)
            },
        )
    }

    /** Every answered record, oldest first. */
    suspend fun answered(): List<FeedbackRecord> = dao.answered().map { it.toRecord() }

    private suspend fun pastWeather(hang: HangEntity, checkTime: LocalDateTime): List<HourlyWeather>? {
        val hangTime = LocalDateTime.parse(hang.hangTime)
        // past_days counts back from today, so a hang two days ago needs past_days = 2.
        val pastDays = hangTime.date.daysUntil(checkTime.date).coerceIn(0, 92)
        return try {
            weather.getPastHours(GeoPoint(hang.latitude, hang.longitude), pastDays)
                .filter { it.time.plusMinutes(60.0) > hangTime && it.time < checkTime }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

// Stored independently of the domain class, so a field rename there cannot break saved rows.
@Serializable
private data class StoredHour(
    val time: String,
    val tempC: Double,
    val rhPercent: Double,
    val windMs: Double,
    val radiationWm2: Double,
    val rainProb: Int,
    val rainMm: Double,
)

private val storageJson = Json { ignoreUnknownKeys = true }

private fun encodeWeather(hours: List<HourlyWeather>): String =
    storageJson.encodeToString(
        hours.map { StoredHour(it.time.toString(), it.tempC, it.rhPercent, it.windMs, it.radiationWm2, it.rainProb, it.rainMm) },
    )

private fun decodeWeather(json: String): List<HourlyWeather> =
    storageJson.decodeFromString<List<StoredHour>>(json).map {
        HourlyWeather(LocalDateTime.parse(it.time), it.tempC, it.rhPercent, it.windMs, it.radiationWm2, it.rainProb, it.rainMm)
    }

private fun HangEntity.toRecord() = FeedbackRecord(
    id = id,
    load = Load(Fabric.valueOf(fabric), Crowding.valueOf(crowding), Spot.valueOf(spot)),
    point = GeoPoint(latitude, longitude),
    hangTime = LocalDateTime.parse(hangTime),
    predictedFinish = predictedFinish?.let(LocalDateTime::parse),
    checkTime = checkTime?.let(LocalDateTime::parse),
    result = result?.let(DryResult::valueOf),
    actualFinish = actualFinish?.let(LocalDateTime::parse),
    weather = decodeWeather(weatherJson),
    weatherSource = WeatherSource.valueOf(weatherSource),
)
