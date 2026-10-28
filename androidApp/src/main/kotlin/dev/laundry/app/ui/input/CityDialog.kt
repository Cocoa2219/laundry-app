package dev.laundry.app.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.laundry.app.R
import dev.laundry.app.ui.CityDialogUi
import dev.laundry.weather.Place

/** Search for a city by name and pick one, or switch back to the device location. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CityDialog(
    state: CityDialogUi,
    onSearch: (String) -> Unit,
    onPlaceChosen: (Place) -> Unit,
    onUseDeviceLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The query is typing-in-progress, owned here; only a submitted search reaches the ViewModel.
    var query by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.city_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.city_dialog_body))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.city_dialog_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    trailingIcon = {
                        TextButton(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !state.searching) {
                            Text(stringResource(R.string.city_dialog_search))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                SearchResults(state, onPlaceChosen)
            }
        },
        confirmButton = {
            TextButton(onClick = onUseDeviceLocation) {
                Text(stringResource(R.string.city_dialog_use_device))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.city_dialog_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchResults(state: CityDialogUi, onPlaceChosen: (Place) -> Unit) {
    // Fixed minimum height so the dialog does not jump when results arrive.
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
        val results = state.results
        when {
            state.searching -> LoadingIndicator()
            state.searchFailed -> Message(stringResource(R.string.city_dialog_failed), isError = true)
            results == null -> Unit
            results.isEmpty() -> Message(stringResource(R.string.city_dialog_no_results), isError = false)
            else -> LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(results, key = { "${it.point.latitude},${it.point.longitude},${it.name}" }) { place ->
                    ListItem(
                        onClick = { onPlaceChosen(place) },
                        supportingContent = place.subtitle()?.let { { Text(it) } },
                    ) {
                        Text(place.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, isError: Boolean) {
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun Place.subtitle(): String? =
    listOfNotNull(region, country).filter { it != name }.joinToString(", ").ifEmpty { null }
