package dev.laundry.app.ui.feedback

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.laundry.app.R
import dev.laundry.app.ui.FeedbackDialogUi
import dev.laundry.app.ui.labelRes
import dev.laundry.data.DryResult
import kotlinx.datetime.LocalTime

/** "Did it dry?" — the answer the learning model trains on. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedbackDialog(
    state: FeedbackDialogUi,
    onSubmit: (DryResult, LocalTime?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Choices in progress belong to the dialog; only the submitted answer reaches the ViewModel.
    var choice by rememberSaveable { mutableStateOf<DryResult?>(null) }
    var knowsTime by rememberSaveable { mutableStateOf(false) }
    val timeState = rememberTimePickerState(is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    val fabric = stringResource(state.pending.fabric.labelRes).lowercase()

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(R.string.feedback_title, fabric)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    Option(R.string.feedback_dry, choice == DryResult.DRY) { choice = DryResult.DRY }
                    Option(R.string.feedback_damp, choice == DryResult.STILL_DAMP) { choice = DryResult.STILL_DAMP }
                    Option(R.string.feedback_skipped, choice == DryResult.SKIPPED) { choice = DryResult.SKIPPED }
                }
                AnimatedVisibility(visible = choice == DryResult.SKIPPED) {
                    Text(
                        text = stringResource(R.string.feedback_skipped_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = choice == DryResult.DRY) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = knowsTime, role = Role.Checkbox, onValueChange = { knowsTime = it }),
                        ) {
                            Checkbox(checked = knowsTime, onCheckedChange = null)
                            Text(stringResource(R.string.feedback_know_time), style = MaterialTheme.typography.bodyMedium)
                        }
                        AnimatedVisibility(visible = knowsTime) {
                            TimeInput(state = timeState)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = choice ?: return@TextButton
                    val driedAt = if (selected == DryResult.DRY && knowsTime) LocalTime(timeState.hour, timeState.minute) else null
                    onSubmit(selected, driedAt)
                },
                enabled = choice != null && !state.saving,
            ) {
                if (state.saving) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.feedback_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) {
                Text(stringResource(R.string.feedback_later))
            }
        },
    )
}

@Composable
private fun Option(label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
