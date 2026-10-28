package dev.laundry.weather

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val openMeteoJson = Json { ignoreUnknownKeys = true }

// hourly/daily stay as raw objects: their keys depend on which models answered
// (see OpenMeteoMapping.kt).
@Serializable
internal data class ForecastDto(
    val timezone: String,
    val hourly: JsonObject,
    val daily: JsonObject,
)

@Serializable
internal data class GeocodingDto(
    // The API omits "results" entirely when nothing matches.
    val results: List<PlaceDto> = emptyList(),
)

@Serializable
internal data class PlaceDto(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val admin1: String? = null,
    val country: String? = null,
)
