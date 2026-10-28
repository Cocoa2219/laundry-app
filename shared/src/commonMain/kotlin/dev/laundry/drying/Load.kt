package dev.laundry.drying

// All factors below are initial estimates; tune them against measured drying times
// and update the tests when they change.

/** Moisture units the item holds when it comes out of the washer. */
enum class Fabric(val units: Double) {
    THIN_TOP(5.0), TSHIRT(7.0), TOWEL(11.0), HOODIE(15.0), JEANS(17.0), BEDDING(22.0)
}

/** Tightly packed items shade each other and trap humid air, so they need more drying. */
enum class Crowding(val factor: Double) { SPACED(1.0), NORMAL(1.15), CROWDED(1.3) }

/** How much of the forecast wind and sun actually reach the laundry at this spot. */
enum class Spot(val windFactor: Double, val sunFactor: Double, val rainExposed: Boolean) {
    OPEN_YARD(0.8, 1.0, true),
    BALCONY(0.5, 0.7, false),
    INDOOR(0.0, 0.0, false),
}

/** What the user is drying: the slowest item decides when the whole load is done. */
data class Load(val fabric: Fabric, val crowding: Crowding, val spot: Spot) {
    val threshold: Double get() = fabric.units * crowding.factor
}
