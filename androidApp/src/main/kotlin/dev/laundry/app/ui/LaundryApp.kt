package dev.laundry.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.laundry.app.ui.input.CityDialog
import dev.laundry.app.ui.input.InputScreen

/** App host: the only composable that touches the ViewModel. */
@Composable
fun LaundryApp(viewModel: LaundryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, resolving decides: a refusal ends in the city prompt.
        viewModel.onLocationPermissionHandled()
    }
    LaunchedEffect(state.location) {
        if (state.location != LocationUi.AwaitingPermission) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onLocationPermissionHandled()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    InputScreen(
        load = state.load,
        location = state.location,
        checking = state.checking,
        canCheck = state.canCheck,
        checkFailed = state.checkFailed,
        onFabricSelected = viewModel::selectFabric,
        onCrowdingSelected = viewModel::selectCrowding,
        onSpotSelected = viewModel::selectSpot,
        onLocationClick = viewModel::openCityDialog,
        onCheck = viewModel::check,
    )

    state.cityDialog?.let { dialog ->
        CityDialog(
            state = dialog,
            onSearch = viewModel::searchCity,
            onPlaceChosen = viewModel::chooseCity,
            onUseDeviceLocation = viewModel::useDeviceLocation,
            onDismiss = viewModel::dismissCityDialog,
        )
    }
}
