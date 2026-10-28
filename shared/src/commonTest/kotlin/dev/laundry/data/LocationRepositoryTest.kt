package dev.laundry.data

import dev.laundry.weather.GeoPoint
import dev.laundry.weather.Place
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationRepositoryTest {
    private val busan = Place("Busan", region = "Busan", country = "South Korea", point = GeoPoint(35.18, 129.08))
    private val deviceFix = GeoPoint(37.57, 126.98)

    private fun repo(deviceFix: GeoPoint?) =
        LocationRepository(InMemoryPreferencesStore()) { deviceFix }

    @Test
    fun default_isDevice() = runTest {
        assertEquals(LocationChoice.Device, repo(deviceFix).getChoice())
    }

    @Test
    fun device_withFix_resolvesWithoutCityName() = runTest {
        assertEquals(ResolvedLocation.Found(deviceFix, cityName = null), repo(deviceFix).resolve())
    }

    @Test
    fun device_withoutFix_needsCity() = runTest {
        assertEquals(ResolvedLocation.NeedsCity, repo(deviceFix = null).resolve())
    }

    @Test
    fun city_isStoredAndUsedInsteadOfDevice() = runTest {
        val repo = repo(deviceFix)

        repo.useCity(busan)

        assertEquals(LocationChoice.City(busan), repo.getChoice())
        assertEquals(ResolvedLocation.Found(busan.point, "Busan"), repo.resolve())
    }

    @Test
    fun city_withoutRegionOrCountry_roundTrips() = runTest {
        val repo = repo(deviceFix)
        val bare = Place("Somewhere", region = null, country = null, point = GeoPoint(1.0, 2.0))

        repo.useCity(busan)
        repo.useCity(bare)

        assertEquals(LocationChoice.City(bare), repo.getChoice())
    }

    @Test
    fun useDevice_clearsCity() = runTest {
        val repo = repo(deviceFix)

        repo.useCity(busan)
        repo.useDevice()

        assertEquals(LocationChoice.Device, repo.getChoice())
        assertEquals(ResolvedLocation.Found(deviceFix, cityName = null), repo.resolve())
    }
}
