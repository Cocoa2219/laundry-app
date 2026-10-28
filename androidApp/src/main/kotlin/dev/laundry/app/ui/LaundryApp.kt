package dev.laundry.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.laundry.app.ui.feedback.FeedbackDialog
import dev.laundry.app.ui.input.CityDialog
import dev.laundry.app.ui.input.InputScreen
import dev.laundry.app.ui.result.ResultScreen

/** App host: the only composable that touches the ViewModel. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    BackHandler(enabled = state.showingResult, onBack = viewModel::closeResult)

    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state.showingResult,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        label = "screen",
    ) { showingResult ->
        if (showingResult) {
            ResultScreen(
                load = state.load,
                checking = state.checking,
                failed = state.checkFailed,
                result = state.result,
                hungAt = state.hungAt,
                undoOffer = state.undoOffer,
                onBack = viewModel::closeResult,
                onRetry = viewModel::check,
                onHang = viewModel::markHung,
                onUndo = viewModel::undoHang,
                onUndoOfferHandled = viewModel::undoOfferHandled,
            )
        } else {
            InputScreen(
                load = state.load,
                location = state.location,
                canCheck = state.canCheck,
                pendingFeedback = state.pendingFeedback,
                onAnswerFeedback = viewModel::openFeedback,
                onFabricSelected = viewModel::selectFabric,
                onCrowdingSelected = viewModel::selectCrowding,
                onSpotSelected = viewModel::selectSpot,
                onLocationClick = viewModel::openCityDialog,
                onCheck = viewModel::check,
            )
        }
    }

    state.cityDialog?.let { dialog ->
        CityDialog(
            state = dialog,
            onSearch = viewModel::searchCity,
            onPlaceChosen = viewModel::chooseCity,
            onUseDeviceLocation = viewModel::useDeviceLocation,
            onDismiss = viewModel::dismissCityDialog,
        )
    }

    state.feedbackDialog?.let { dialog ->
        FeedbackDialog(
            state = dialog,
            onSubmit = viewModel::submitFeedback,
            onDismiss = viewModel::dismissFeedback,
        )
    }
}
