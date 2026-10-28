package dev.laundry.weather

import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoClientTest {

    @Test
    fun forecastRequest_hasAllRequiredParameters() = runTest {
        lateinit var url: Url
        val client = mockClient { request ->
            url = request.url
            respondJson(MULTI_MODEL_JSON)
        }

        client.fetchForecastJson(SEOUL)

        val params = url.parameters
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals("37.57", params["latitude"])
        assertEquals("126.98", params["longitude"])
        assertEquals(
            "temperature_2m,relative_humidity_2m,wind_speed_10m," +
                "shortwave_radiation,precipitation_probability,precipitation",
            params["hourly"],
        )
        assertEquals("sunrise,sunset", params["daily"])
        assertEquals("jma_msm,best_match", params["models"])
        assertEquals("ms", params["wind_speed_unit"])
        assertEquals("auto", params["timezone"])
        assertEquals("2", params["forecast_days"])
        assertNull(params["past_days"])
    }

    @Test
    fun pastDays_isSentWhenRequested() = runTest {
        lateinit var url: Url
        val client = mockClient { request ->
            url = request.url
            respondJson(MULTI_MODEL_JSON)
        }

        client.fetchForecastJson(SEOUL, pastDays = 1)

        assertEquals("1", url.parameters["past_days"])
    }

    @Test
    fun pastDays_outOfRange_isRejected() = runTest {
        val client = mockClient { respondJson(MULTI_MODEL_JSON) }

        assertFailsWith<IllegalArgumentException> { client.fetchForecastJson(SEOUL, pastDays = 93) }
    }

    @Test
    fun httpError_throws() = runTest {
        val client = mockClient { respondJson("""{"error":true}""", HttpStatusCode.BadRequest) }

        val e = assertFailsWith<OpenMeteoHttpException> { client.fetchForecastJson(SEOUL) }
        assertEquals(400, e.statusCode)
    }

    @Test
    fun searchPlaces_mapsResults() = runTest {
        lateinit var url: Url
        val client = mockClient { request ->
            url = request.url
            respondJson(
                """
                {"results":[
                  {"id":1,"name":"Seoul","latitude":37.566,"longitude":126.9784,
                   "country":"South Korea","admin1":"Seoul","timezone":"Asia/Seoul"},
                  {"id":2,"name":"Seoul Station","latitude":37.55,"longitude":126.97}
                ]}
                """,
            )
        }

        val places = client.searchPlaces("Seoul")

        assertEquals("geocoding-api.open-meteo.com", url.host)
        assertEquals("Seoul", url.parameters["name"])
        assertEquals(
            Place("Seoul", region = "Seoul", country = "South Korea", point = GeoPoint(37.566, 126.9784)),
            places[0],
        )
        assertNull(places[1].country)
    }

    @Test
    fun searchPlaces_noMatches_returnsEmpty() = runTest {
        // The API omits "results" when nothing matches.
        val client = mockClient { respondJson("""{"generationtime_ms":0.2}""") }

        assertTrue(client.searchPlaces("zzzz").isEmpty())
    }
}
