package dev.laundry.weather

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Thin HTTP layer over the Open-Meteo forecast and geocoding APIs. No API key needed. */
class OpenMeteoClient(private val http: HttpClient) {

    /**
     * Returns the raw forecast JSON, so the caller can cache exactly what was received.
     * [pastDays] > 0 includes hours that have already passed (used when saving feedback).
     */
    suspend fun fetchForecastJson(point: GeoPoint, pastDays: Int = 0): String {
        require(pastDays in 0..92) { "past_days must be 0..92, was $pastDays" }
        val response = http.get(FORECAST_URL) {
            parameter("latitude", point.latitude)
            parameter("longitude", point.longitude)
            parameter("hourly", HOURLY_FIELDS)
            parameter("daily", "sunrise,sunset")
            parameter("models", FORECAST_MODELS)
            parameter("wind_speed_unit", "ms")
            parameter("timezone", "auto")
            parameter("forecast_days", 2)
            if (pastDays > 0) parameter("past_days", pastDays)
        }
        return response.requireSuccess().bodyAsText()
    }

    suspend fun searchPlaces(name: String, count: Int = 5): List<Place> {
        val response = http.get(GEOCODING_URL) {
            parameter("name", name)
            parameter("count", count)
            parameter("language", "en")
            parameter("format", "json")
        }
        val body = response.requireSuccess().bodyAsText()
        return openMeteoJson.decodeFromString<GeocodingDto>(body).results.map { it.toPlace() }
    }

    private fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) throw OpenMeteoHttpException(status.value)
        return this
    }

    private companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val HOURLY_FIELDS = "temperature_2m,relative_humidity_2m,wind_speed_10m," +
            "shortwave_radiation,precipitation_probability,precipitation"
    }
}

class OpenMeteoHttpException(val statusCode: Int) : Exception("Open-Meteo returned HTTP $statusCode")
