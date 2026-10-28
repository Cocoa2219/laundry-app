package dev.laundry.app.ui.input

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.laundry.app.R
import dev.laundry.app.ui.LocationUi
import dev.laundry.app.ui.PendingFeedbackUi
import dev.laundry.app.ui.labelRes
import dev.laundry.app.ui.timeText
import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Spot

/** Stateless input screen: the load picker, the location chip and the check button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InputScreen(
    load: Load,
    location: LocationUi,
    canCheck: Boolean,
    pendingFeedback: PendingFeedbackUi?,
    onAnswerFeedback: () -> Unit,
    onFabricSelected: (Fabric) -> Unit,
    onCrowdingSelected: (Crowding) -> Unit,
    onSpotSelected: (Spot) -> Unit,
    onLocationClick: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Phone-width column even in a wide window, so the button groups never stretch.
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                if (pendingFeedback != null) {
                    PendingFeedbackCard(pendingFeedback, onAnswerFeedback)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.input_title),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    LocationChip(location, onLocationClick)
                }

                Section(R.string.input_fabric_label, hint = R.string.input_fabric_hint) {
                    FabricPicker(selected = load.fabric, onSelected = onFabricSelected)
                }
                Section(R.string.input_crowding_label) {
                    ConnectedChoice(
                        options = Crowding.entries,
                        selected = load.crowding,
                        label = { it.labelRes },
                        onSelected = onCrowdingSelected,
                    )
                }
                Section(R.string.input_spot_label) {
                    ConnectedChoice(
                        options = Spot.entries,
                        selected = load.spot,
                        label = { it.labelRes },
                        onSelected = onSpotSelected,
                    )
                }

                CheckButton(enabled = canCheck, onClick = onCheck)
            }
        }
    }
}

/** Shown until the last hang (within 24 h) has its "Did it dry?" answer. */
@Composable
private fun PendingFeedbackCard(pending: PendingFeedbackUi, onAnswer: () -> Unit) {
    val fabric = stringResource(pending.fabric.labelRes).lowercase()
    // Hang times are always today or yesterday, so the plain clock time is enough here.
    val hungAt = timeText(pending.hangTime, today = pending.hangTime.date)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.pending_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.pending_body, fabric, hungAt), style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = onAnswer, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.pending_answer))
            }
        }
    }
}

@Composable
private fun Section(
    @StringRes title: Int,
    @StringRes hint: Int? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (hint != null) {
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun LocationChip(location: LocationUi, onClick: () -> Unit) {
    val label = when (location) {
        LocationUi.Loading, LocationUi.AwaitingPermission, LocationUi.Locating ->
            stringResource(R.string.location_loading)
        is LocationUi.Found -> location.cityName ?: stringResource(R.string.location_current)
        LocationUi.NeedsCity -> stringResource(R.string.location_set)
    }
    val changeDescription = stringResource(R.string.location_change_description)
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { contentDescription = "$changeDescription: $label" },
    )
}

/** Six fabrics wrap onto two lines, so they are separate toggles rather than one connected group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun FabricPicker(selected: Fabric, onSelected: (Fabric) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Fabric.entries.forEach { fabric ->
            ToggleButton(
                checked = fabric == selected,
                onCheckedChange = { onSelected(fabric) },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(fabric.labelRes))
            }
        }
    }
}

/** Single choice from a few options as an M3 Expressive connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelected(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(label(option)), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckButton(enabled: Boolean, onClick: () -> Unit) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
        Text(stringResource(R.string.input_check), style = ButtonDefaults.textStyleFor(height))
    }
}
