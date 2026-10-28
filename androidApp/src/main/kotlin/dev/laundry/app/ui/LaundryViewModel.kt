package dev.laundry.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.laundry.data.DryResult
import dev.laundry.data.FeedbackLog
import dev.laundry.data.LoadPreferences
import dev.laundry.data.LocationChoice
import dev.laundry.data.LocationRepository
import dev.laundry.data.ResolvedLocation
import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Recommendation
import dev.laundry.drying.Spot
import dev.laundry.drying.Verdict
import dev.laundry.drying.recommend
import dev.laundry.weather.Forecast
import dev.laundry.weather.GeoPoint
import dev.laundry.weather.Place
import dev.laundry.weather.WeatherRepository
import dev.laundry.weather.WeatherUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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

data class CheckResult(
    val recommendation: Recommendation,
    /** "Now" at check time, in the forecast's local time; finish times on a later date say so. */
    val checkedAt: LocalDateTime,
    /** Set when the network failed and the cached forecast was used: when it was fetched. */
    val offlineForecastFrom: LocalDateTime?,
)

/** A hang still waiting for its "Did it dry?" answer. */
data class PendingFeedbackUi(val recordId: Long, val fabric: Fabric, val hangTime: LocalDateTime)

data class FeedbackDialogUi(val pending: PendingFeedbackUi, val saving: Boolean = false)

data class LaundryUiState(
    val load: Load = LoadPreferences.DEFAULT,
    val location: LocationUi = LocationUi.Loading,
    /** Null when the city dialog is closed. */
    val cityDialog: CityDialogUi? = null,
    /** True while the result screen is shown (loading, error or result). */
    val showingResult: Boolean = false,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
    val result: CheckResult? = null,
    /** When the user tapped "I hung it" for the current result. */
    val hungAt: LocalDateTime? = null,
    /** Set right after "I hung it" until the UI has offered Undo once. */
    val undoOffer: Long? = null,
    val pendingFeedback: PendingFeedbackUi? = null,
    /** Null when the feedback dialog is closed. */
    val feedbackDialog: FeedbackDialogUi? = null,
) {
    val canCheck: Boolean get() = location is LocationUi.Found && !checking
}

class LaundryViewModel(
    private val weather: WeatherRepository,
    private val locations: LocationRepository,
    private val loadPreferences: LoadPreferences,
    private val searchPlaces: suspend (String) -> List<Place>,
    private val feedback: FeedbackLog,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    private val _state = MutableStateFlow(LaundryUiState())
    val state: StateFlow<LaundryUiState> = _state.asStateFlow()

    // The city dialog opens on its own only once per launch; after that the user opens it.
    private var cityPromptShown = false

    // What the current result was computed from; "I hung it" records against the same forecast.
    private var lastForecast: Forecast? = null
    private var lastPoint: GeoPoint? = null
    private var zone: TimeZone = TimeZone.currentSystemDefault()

    init {
        viewModelScope.launch {
            val load = loadPreferences.get()
            val location = when (val choice = locations.getChoice()) {
                is LocationChoice.City -> LocationUi.Found(choice.place.name)
                LocationChoice.Device -> LocationUi.AwaitingPermission
            }
            _state.update { it.copy(load = load, location = location) }
        }
        refreshPendingFeedback()
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
        _state.update {
            it.copy(showingResult = true, checking = true, checkFailed = false, result = null, hungAt = null)
        }
        viewModelScope.launch {
            try {
                when (val resolved = locations.resolve()) {
                    ResolvedLocation.NeedsCity -> {
                        _state.update { it.copy(showingResult = false) }
                        // The user just asked for a result, so ask for a city even if asked before.
                        showNeedsCity(openDialog = true)
                    }
                    is ResolvedLocation.Found -> {
                        val forecast = weather.getForecast(resolved.point)
                        zone = timeZoneOrDefault(forecast.timezone)
                        lastForecast = forecast
                        lastPoint = resolved.point
                        val now = clock.now().toLocalDateTime(zone)
                        val result = CheckResult(
                            recommendation = recommend(forecast, _state.value.load, now),
                            checkedAt = now,
                            offlineForecastFrom = if (forecast.fromCache) forecast.fetchedAt.toLocalDateTime(zone) else null,
                        )
                        _state.update { it.copy(result = result) }
                    }
                }
            } catch (e: WeatherUnavailableException) {
                _state.update { it.copy(checkFailed = true) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    /** Back from the result screen to the input screen. */
    fun closeResult() = _state.update { it.copy(showingResult = false, checkFailed = false) }

    /**
     * Records the load as hung now. When the advice was to dry indoors, the record says indoors:
     * that is where the load went, and the learning is per spot.
     */
    fun markHung() {
        val current = _state.value
        val result = current.result ?: return
        val forecast = lastForecast ?: return
        val point = lastPoint ?: return
        if (current.hungAt != null || result.recommendation.verdict == Verdict.DRYER) return

        val spot = if (result.recommendation.verdict == Verdict.INDOOR) Spot.INDOOR else current.load.spot
        val now = nowLocal()
        _state.update { it.copy(hungAt = now) }
        viewModelScope.launch {
            val id = feedback.recordHang(current.load.copy(spot = spot), point, now, forecast)
            _state.update { it.copy(undoOffer = id) }
            refreshPendingFeedback()
        }
    }

    fun undoHang(recordId: Long) {
        _state.update { it.copy(hungAt = null, undoOffer = null) }
        viewModelScope.launch {
            feedback.deleteHang(recordId)
            refreshPendingFeedback()
        }
    }

    /** The Undo offer was shown and dismissed without Undo. */
    fun undoOfferHandled() = _state.update { it.copy(undoOffer = null) }

    fun openFeedback() = _state.update { state ->
        state.copy(feedbackDialog = state.pendingFeedback?.let { FeedbackDialogUi(it) })
    }

    fun dismissFeedback() = _state.update { it.copy(feedbackDialog = null) }

    /**
     * Saves the answer. [driedAt] is the clock time the user remembers; a time earlier in the day
     * than the hang time means the next day. Times that make no sense (before hanging, or in the
     * future) are dropped rather than stored.
     */
    fun submitFeedback(result: DryResult, driedAt: LocalTime?) {
        val dialog = _state.value.feedbackDialog ?: return
        if (dialog.saving) return
        val pending = dialog.pending
        val now = nowLocal()
        val actualFinish = driedAt
            ?.let { time ->
                val date = if (time > pending.hangTime.time) pending.hangTime.date else pending.hangTime.date.plus(1, DateTimeUnit.DAY)
                LocalDateTime(date, time)
            }
            ?.takeIf { result == DryResult.DRY && it > pending.hangTime && it <= now }

        _state.update { it.copy(feedbackDialog = dialog.copy(saving = true)) }
        viewModelScope.launch {
            feedback.answer(pending.recordId, result, checkTime = now, actualFinish = actualFinish)
            _state.update { it.copy(feedbackDialog = null) }
            refreshPendingFeedback()
        }
    }

    private fun refreshPendingFeedback() {
        viewModelScope.launch {
            val record = feedback.latestUnanswered(nowLocal())
            _state.update { state ->
                state.copy(pendingFeedback = record?.let { PendingFeedbackUi(it.id, it.load.fabric, it.hangTime) })
            }
        }
    }

    // Minute precision: stored hang and check times never need seconds.
    private fun nowLocal(): LocalDateTime =
        clock.now().toLocalDateTime(zone).let { LocalDateTime(it.date, LocalTime(it.hour, it.minute)) }

    private fun showNeedsCity(openDialog: Boolean = !cityPromptShown) {
        cityPromptShown = true
        _state.update {
            it.copy(
                location = LocationUi.NeedsCity,
                cityDialog = if (openDialog) CityDialogUi() else it.cityDialog,
            )
        }
    }

    private fun updateLoad(change: (Load) -> Load) {
        _state.update { it.copy(load = change(it.load), result = null, hungAt = null) }
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
