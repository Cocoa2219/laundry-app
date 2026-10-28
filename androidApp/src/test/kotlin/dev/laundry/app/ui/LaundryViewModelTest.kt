package dev.laundry.app.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.laundry.data.LoadPreferences
import dev.laundry.data.LocationRepository
import dev.laundry.drying.Fabric
import dev.laundry.drying.Verdict
import dev.laundry.weather.CachedForecast
import dev.laundry.weather.ForecastCache
import dev.laundry.weather.GeoPoint
import dev.laundry.weather.OpenMeteoClient
import dev.laundry.weather.Place
import dev.laundry.weather.WeatherRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LaundryViewModelTest {
    private val seoul = Place("Seoul", "Seoul", "South Korea", GeoPoint(37.57, 126.98))

    // 2026-07-01 07:30 in Seoul (UTC+9).
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-06-30T22:30:00Z")
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
            transform(state.value).also { state.value = it }
    }

    private class MemoryCache : ForecastCache {
        var entry: CachedForecast? = null
        override suspend fun read() = entry
        override suspend fun write(entry: CachedForecast) {
            this.entry = entry
        }
    }

    private fun viewModel(
        settings: MemoryStore = MemoryStore(),
        deviceFix: () -> GeoPoint? = { null },
        online: () -> Boolean = { true },
        cache: MemoryCache = MemoryCache(),
        search: suspend (String) -> List<Place> = { listOf(seoul) },
    ): LaundryViewModel {
        val engine = MockEngine {
            if (!online()) throw RuntimeException("offline")
            respond(sunnyForecastJson(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return LaundryViewModel(
            weather = WeatherRepository(OpenMeteoClient(HttpClient(engine)), cache, clock),
            locations = LocationRepository(settings) { deviceFix() },
            loadPreferences = LoadPreferences(settings),
            searchPlaces = search,
            clock = clock,
        )
    }

    private suspend fun LaundryViewModel.awaitIdle() =
        state.first { it.location != LocationUi.Loading && it.location != LocationUi.Locating && !it.checking }

    @Test
    fun deviceChoice_waitsForPermissionThenUsesDeviceFix() = runTest {
        val vm = viewModel(deviceFix = { GeoPoint(37.57, 126.98) })
        assertEquals(LocationUi.AwaitingPermission, vm.awaitIdle().location)

        vm.onLocationPermissionHandled()

        assertEquals(LocationUi.Found(cityName = null), vm.awaitIdle().location)
    }

    @Test
    fun noDeviceFix_needsCityAndOpensDialogOnlyOnce() = runTest {
        val vm = viewModel(deviceFix = { null })
        vm.awaitIdle()

        vm.onLocationPermissionHandled()
        val first = vm.awaitIdle()
        vm.dismissCityDialog()
        vm.onLocationPermissionHandled()
        val second = vm.awaitIdle()

        assertEquals(LocationUi.NeedsCity, first.location)
        assertNotNull(first.cityDialog)
        assertNull(second.cityDialog)
        assertTrue(!second.canCheck)
    }

    @Test
    fun chosenCity_isSavedAndUsedOnNextLaunch() = runTest {
        val settings = MemoryStore()
        val vm = viewModel(settings)
        vm.awaitIdle()

        vm.openCityDialog()
        vm.searchCity("Seoul")
        val searched = vm.state.first { it.cityDialog?.results != null }
        vm.chooseCity(searched.cityDialog!!.results!!.single())

        assertNull(vm.state.value.cityDialog)
        assertEquals(LocationUi.Found("Seoul"), viewModel(settings).awaitIdle().location)
    }

    @Test
    fun searchFailure_isShownInDialog() = runTest {
        val vm = viewModel(search = { throw RuntimeException("offline") })
        vm.awaitIdle()

        vm.openCityDialog()
        vm.searchCity("Seoul")

        assertTrue(vm.state.first { it.cityDialog?.searching == false }.cityDialog!!.searchFailed)
    }

    @Test
    fun check_withCity_producesRecommendation() = runTest {
        val vm = viewModel()
        vm.awaitIdle()
        vm.chooseCity(seoul)

        vm.check()
        val done = vm.awaitIdle()

        assertEquals(Verdict.HANG_OUT, done.result?.recommendation?.verdict)
        assertTrue(!done.checkFailed)
    }

    @Test
    fun check_offlineWithoutCache_reportsFailure() = runTest {
        val vm = viewModel(online = { false })
        vm.awaitIdle()
        vm.chooseCity(seoul)

        vm.check()
        val done = vm.awaitIdle()

        assertTrue(done.showingResult)
        assertTrue(done.checkFailed)
        assertNull(done.result)
    }

    @Test
    fun check_offlineWithCache_saysWhichForecastWasUsed() = runTest {
        var online = true
        val vm = viewModel(online = { online })
        vm.awaitIdle()
        vm.chooseCity(seoul)
        vm.check()
        vm.awaitIdle()

        online = false
        vm.check()
        val result = assertNotNull(vm.awaitIdle().result)

        // Cached at the fixed clock's time: 07:30 in Seoul.
        assertEquals(LocalDateTime(2026, 7, 1, 7, 30), result.offlineForecastFrom)
    }

    @Test
    fun check_opensResultScreen_backReturnsToInput() = runTest {
        val vm = viewModel()
        vm.awaitIdle()
        vm.chooseCity(seoul)

        vm.check()
        assertTrue(vm.awaitIdle().showingResult)
        assertNull(vm.state.value.result?.offlineForecastFrom)

        vm.closeResult()
        assertTrue(!vm.state.value.showingResult)
    }

    @Test
    fun check_whenDeviceFixIsGone_staysOnInputAndAsksForCityAgain() = runTest {
        var fix: GeoPoint? = null
        val vm = viewModel(deviceFix = { fix })
        vm.awaitIdle()
        vm.onLocationPermissionHandled()
        vm.awaitIdle()
        vm.dismissCityDialog() // the automatic prompt has now been used up
        fix = GeoPoint(37.57, 126.98)
        vm.onLocationPermissionHandled()
        vm.awaitIdle()

        fix = null
        vm.check()
        val done = vm.awaitIdle()

        assertTrue(!done.showingResult)
        assertEquals(LocationUi.NeedsCity, done.location)
        assertNotNull(done.cityDialog)
    }

    @Test
    fun changingTheLoad_isRememberedAndClearsOldRecommendation() = runTest {
        val settings = MemoryStore()
        val vm = viewModel(settings)
        vm.awaitIdle()
        vm.chooseCity(seoul)
        vm.check()
        vm.awaitIdle()

        vm.selectFabric(Fabric.JEANS)

        assertNull(vm.state.value.result)
        assertEquals(Fabric.JEANS, viewModel(settings).awaitIdle().load.fabric)
    }

    /** Two days of mild, dry, sunny weather in Seoul, in Open-Meteo's single-model shape. */
    private fun sunnyForecastJson(): String {
        val hours = (0 until 48).map { i -> "\"2026-07-0${1 + i / 24}T%02d:00\"".format(i % 24) }
        fun repeat(value: String) = List(48) { value }.joinToString(",")
        return """
            {"timezone":"Asia/Seoul",
             "hourly":{"time":[${hours.joinToString(",")}],
               "temperature_2m":[${repeat("25.0")}],"relative_humidity_2m":[${repeat("45")}],
               "wind_speed_10m":[${repeat("2.5")}],"shortwave_radiation":[${repeat("400.0")}],
               "precipitation_probability":[${repeat("0")}],"precipitation":[${repeat("0.0")}]},
             "daily":{"time":["2026-07-01","2026-07-02"],
               "sunrise":["2026-07-01T05:11","2026-07-02T05:12"],
               "sunset":["2026-07-01T19:57","2026-07-02T19:57"]}}
        """.trimIndent()
    }
}
