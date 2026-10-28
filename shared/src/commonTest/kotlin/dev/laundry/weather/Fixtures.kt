package dev.laundry.weather

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlin.time.Instant

// Shape returned inside the JMA MSM domain (Korea, Japan): every field suffixed per model.
// MSM gaps at 11:00 (temperature) and 12:00 (wind) must be filled from best_match.
const val MULTI_MODEL_JSON = """
{
  "timezone": "Asia/Seoul",
  "hourly": {
    "time": ["2026-10-28T10:00", "2026-10-28T11:00", "2026-10-28T12:00"],
    "temperature_2m_jma_msm": [10.0, null, 12.0],
    "temperature_2m_best_match": [9.0, 11.0, 13.0],
    "relative_humidity_2m_jma_msm": [50, 55, 60],
    "relative_humidity_2m_best_match": [52, 57, 62],
    "wind_speed_10m_jma_msm": [2.0, 2.5, null],
    "wind_speed_10m_best_match": [1.0, 1.5, 3.0],
    "shortwave_radiation_jma_msm": [400.0, 500.0, 600.0],
    "shortwave_radiation_best_match": [380.0, 480.0, 580.0],
    "precipitation_probability_jma_msm": [null, null, null],
    "precipitation_probability_best_match": [10, 60, 0],
    "precipitation_jma_msm": [0.0, 0.2, 0.0],
    "precipitation_best_match": [0.0, 0.0, 0.0]
  },
  "daily": {
    "time": ["2026-10-28"],
    "sunrise_jma_msm": ["2026-10-28T06:52"],
    "sunset_jma_msm": ["2026-10-28T17:38"],
    "sunrise_best_match": ["2026-10-28T06:52"],
    "sunset_best_match": ["2026-10-28T17:38"]
  }
}
"""

// Shape returned outside the MSM domain (e.g. Singapore): plain keys, best_match only.
// 01:00 has no humidity and must be dropped; 02:00 has no rain probability.
const val SINGLE_MODEL_JSON = """
{
  "timezone": "Asia/Singapore",
  "hourly": {
    "time": ["2026-10-28T00:00", "2026-10-28T01:00", "2026-10-28T02:00"],
    "temperature_2m": [27.0, 26.5, 26.0],
    "relative_humidity_2m": [85, null, 88],
    "wind_speed_10m": [1.2, 1.1, 1.0],
    "shortwave_radiation": [0.0, 0.0, 0.0],
    "precipitation_probability": [40, 45, null],
    "precipitation": [0.0, 0.0, 0.5]
  },
  "daily": {
    "time": ["2026-10-28"],
    "sunrise": ["2026-10-28T06:58"],
    "sunset": ["2026-10-28T19:01"]
  }
}
"""

val SEOUL = GeoPoint(37.57, 126.98)
val BUSAN = GeoPoint(35.18, 129.08)

class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

fun mockClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): OpenMeteoClient = OpenMeteoClient(HttpClient(MockEngine(handler)))

fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
