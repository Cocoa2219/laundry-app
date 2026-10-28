package dev.laundry.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.laundry.data.LoadPreferences
import dev.laundry.data.LocationChoice
import dev.laundry.data.LocationRepository
import dev.laundry.data.ResolvedLocation
import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Recommendation
import dev.laundry.drying.Spot
import dev.laundry.drying.recommend
import dev.laundry.weather.Place
import dev.laundry.weather.WeatherRepository
import dev.laundry.weather.WeatherUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

sealed interface LocationUi {
    /** Reading the saved choice at startup. */
    data object Loading : LocationUi

    /** Device location chosen: the UI must check or request the permission, then report back. */
    data object AwaitingPermission : LocationUi

    data object Locating : LocationUi

    /** [cityName] is null for the device location. */
    data class Found(val cityName: String?) : LocationUi

    data object NeedsCity : LocationUi
}

data class CityDialogUi(
    val searching: Boolean = false,
    /** Null until the first search; empty when nothing matched. */
    val results: List<Place>? = null,
    val searchFailed: Boolean = false,
)

data class LaundryUiState(
    val load: Load = LoadPreferences.DEFAULT,
    val location: LocationUi = LocationUi.Loading,
    /** Null when the city dialog is closed. */
    val cityDialog: CityDialogUi? = null,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
    val recommendation: Recommendation? = null,
) {
    val canCheck: Boolean get() = location is LocationUi.Found && !checking
}

class LaundryViewModel(
    private val weather: WeatherRepository,
    private val locations: LocationRepository,
    private val loadPreferences: LoadPreferences,
    private val searchPlaces: suspend (String) -> List<Place>,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    private val _state = MutableStateFlow(LaundryUiState())
    val state: StateFlow<LaundryUiState> = _state.asStateFlow()

    // The city dialog opens on its own only once per launch; after that the user opens it.
    private var cityPromptShown = false

    init {
        viewModelScope.launch {
            val load = loadPreferences.get()
            val location = when (val choice = locations.getChoice()) {
                is LocationChoice.City -> LocationUi.Found(choice.place.name)
                LocationChoice.Device -> LocationUi.AwaitingPermission
            }
            _state.update { it.copy(load = load, location = location) }
        }
    }

    fun selectFabric(fabric: Fabric) = updateLoad { it.copy(fabric = fabric) }

    fun selectCrowding(crowding: Crowding) = updateLoad { it.copy(crowding = crowding) }

    fun selectSpot(spot: Spot) = updateLoad { it.copy(spot = spot) }

    /** Called once the location permission has been checked or the request answered. */
    fun onLocationPermissionHandled() {
        _state.update { it.copy(location = LocationUi.Locating) }
        viewModelScope.launch {
            when (val resolved = locations.resolve()) {
                is ResolvedLocation.Found ->
                    _state.update { it.copy(location = LocationUi.Found(resolved.cityName)) }
                ResolvedLocation.NeedsCity -> showNeedsCity()
            }
        }
    }

    fun openCityDialog() = _state.update { it.copy(cityDialog = CityDialogUi()) }

    fun dismissCityDialog() = _state.update { it.copy(cityDialog = null) }

    fun searchCity(query: String) {
        if (query.isBlank() || _state.value.cityDialog?.searching == true) return
        _state.update { it.copy(cityDialog = CityDialogUi(searching = true)) }
        viewModelScope.launch {
            val dialog = try {
                CityDialogUi(results = searchPlaces(query.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CityDialogUi(searchFailed = true)
            }
            // The user may have closed the dialog while the search ran.
            _state.update { if (it.cityDialog == null) it else it.copy(cityDialog = dialog) }
        }
    }

    fun chooseCity(place: Place) {
        _state.update { it.copy(cityDialog = null, location = LocationUi.Found(place.name)) }
        viewModelScope.launch { locations.useCity(place) }
    }

    fun useDeviceLocation() {
        _state.update { it.copy(cityDialog = null, location = LocationUi.AwaitingPermission) }
        viewModelScope.launch { locations.useDevice() }
    }

    fun check() {
        if (!_state.value.canCheck) return
        _state.update { it.copy(checking = true, checkFailed = false) }
        viewModelScope.launch {
            try {
                when (val resolved = locations.resolve()) {
                    ResolvedLocation.NeedsCity -> showNeedsCity()
                    is ResolvedLocation.Found -> {
                        val forecast = weather.getForecast(resolved.point)
                        val now = clock.now().toLocalDateTime(timeZoneOrDefault(forecast.timezone))
                        val recommendation = recommend(forecast, _state.value.load, now)
                        _state.update { it.copy(recommendation = recommendation) }
                    }
                }
            } catch (e: WeatherUnavailableException) {
                _state.update { it.copy(checkFailed = true) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    private fun showNeedsCity() {
        val openDialog = !cityPromptShown
        cityPromptShown = true
        _state.update {
            it.copy(
                location = LocationUi.NeedsCity,
                cityDialog = if (openDialog) CityDialogUi() else it.cityDialog,
            )
        }
    }

    private fun updateLoad(change: (Load) -> Load) {
        _state.update { it.copy(load = change(it.load), recommendation = null) }
        val load = _state.value.load
        viewModelScope.launch { loadPreferences.save(load) }
    }
}

// The forecast's IANA zone is what "now" must be compared against; fall back to the device's.
private fun timeZoneOrDefault(id: String): TimeZone =
    try {
        TimeZone.of(id)
    } catch (e: IllegalArgumentException) {
        TimeZone.currentSystemDefault()
    }
