package dev.laundry.app.ui.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.laundry.app.R
import dev.laundry.app.ui.CheckResult
import dev.laundry.app.ui.labelRes
import dev.laundry.app.ui.timeText
import dev.laundry.drying.CutoffReason
import dev.laundry.drying.FinishRange
import dev.laundry.drying.Load
import dev.laundry.drying.Recommendation
import dev.laundry.drying.Spot
import dev.laundry.drying.Verdict
import dev.laundry.drying.Why
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.math.roundToInt

/** Stateless result screen: loading, the inline error with Retry, or the recommendation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    load: Load,
    checking: Boolean,
    failed: Boolean,
    result: CheckResult?,
    hungAt: LocalDateTime?,
    undoOffer: Long?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onHang: () -> Unit,
    onUndo: (Long) -> Unit,
    onUndoOfferHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.hang_saved)
    val undoLabel = stringResource(R.string.hang_undo)
    LaunchedEffect(undoOffer) {
        val recordId = undoOffer ?: return@LaunchedEffect
        val action = snackbarHostState.showSnackbar(savedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        if (action == SnackbarResult.ActionPerformed) onUndo(recordId) else onUndoOfferHandled()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.result_title, stringResource(load.fabric.labelRes), stringResource(load.spot.labelRes)))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.result_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    result != null -> {
                        ResultContent(result, chosenIndoors = load.spot == Spot.INDOOR)
                        HangAction(result, hungAt, onHang)
                    }
                    failed -> Failure(onRetry)
                    checking -> Checking()
                }
            }
        }
    }
}

/** "I hung it", or once tapped, when it was hung. Not offered when the advice is the dryer. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HangAction(result: CheckResult, hungAt: LocalDateTime?, onHang: () -> Unit) {
    val verdict = result.recommendation.verdict
    if (verdict == Verdict.DRYER) return
    if (hungAt != null) {
        Text(
            text = stringResource(R.string.hang_done, timeText(hungAt, result.checkedAt.date)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onHang,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
        Text(
            text = stringResource(if (verdict == Verdict.INDOOR) R.string.hang_button_indoors else R.string.hang_button),
            style = ButtonDefaults.textStyleFor(height),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Checking() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator()
        Text(stringResource(R.string.result_checking), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Failure(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.result_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.result_retry)) }
    }
}

@Composable
private fun ResultContent(result: CheckResult, chosenIndoors: Boolean) {
    val rec = result.recommendation
    val today = result.checkedAt.date

    VerdictCard(rec, today, chosenIndoors)
    if (rec.verdict == Verdict.HANG_OUT || rec.verdict == Verdict.RISKY) {
        Times(rec, today)
    }
    rec.outdoorAlternative?.let { alternative ->
        OutdoorHint(stringResource(R.string.outdoor_hint, timeText(alternative.hangBy, today), timeText(alternative.inBy, today)))
    }
    result.offlineForecastFrom?.let { fetchedAt ->
        Text(
            text = stringResource(R.string.result_offline, timeText(fetchedAt, today)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    WhyCard(rec.why, today)
}

@Composable
private fun VerdictCard(rec: Recommendation, today: LocalDate, chosenIndoors: Boolean) {
    val (container, content) = verdictColors(rec.verdict)
    val hangBy = rec.hangBy?.let { timeText(it, today) }
    val inBy = rec.inBy?.let { timeText(it, today) }
    // When the user picked Indoors, "Dry it indoors" would only repeat their choice: show the time.
    val (title, body) = when (rec.verdict) {
        Verdict.HANG_OUT -> stringResource(R.string.verdict_hang_out) to
            stringResource(R.string.verdict_hang_out_body, hangBy!!, inBy!!)
        Verdict.RISKY -> stringResource(R.string.verdict_risky) to
            stringResource(R.string.verdict_risky_body, hangBy!!, inBy!!)
        Verdict.INDOOR -> if (chosenIndoors) {
            stringResource(R.string.verdict_indoor_chosen_title, inBy!!) to stringResource(R.string.verdict_indoor_chosen_body)
        } else {
            stringResource(R.string.verdict_indoor) to stringResource(R.string.verdict_indoor_body, inBy!!)
        }
        Verdict.DRYER -> stringResource(R.string.verdict_dryer) to
            stringResource(if (chosenIndoors) R.string.verdict_dryer_chosen_body else R.string.verdict_dryer_body)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = MaterialTheme.shapes.extraLarge,
        // Announced when it appears after loading.
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun OutdoorHint(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun verdictColors(verdict: Verdict): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (verdict) {
        Verdict.HANG_OUT -> scheme.primaryContainer to scheme.onPrimaryContainer
        Verdict.RISKY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Verdict.INDOOR -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Verdict.DRYER -> scheme.errorContainer to scheme.onErrorContainer
    }
}

@Composable
private fun Times(rec: Recommendation, today: LocalDate) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        rec.hangBy?.let { TimeTile(R.string.result_hang_by, timeText(it, today), detail = null, Modifier.weight(1f)) }
        rec.inBy?.let { TimeTile(R.string.result_dry_around, timeText(it, today), rec.range?.let { r -> rangeText(r, today) }, Modifier.weight(1f)) }
    }
}

@Composable
private fun TimeTile(label: Int, value: String, detail: String?, modifier: Modifier) {
    ElevatedCard(modifier = modifier, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun rangeText(range: FinishRange, today: LocalDate): String {
    val earliest = timeText(range.earliest, today)
    val latest = range.latest
    return if (latest == null) {
        stringResource(R.string.result_range_open, earliest)
    } else {
        stringResource(R.string.result_range, earliest, timeText(latest, today))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WhyCard(why: Why, today: LocalDate) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.why_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.why_collapse else R.string.why_expand))
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                WhyDetails(why, today)
            }
        }
    }
}

@Composable
private fun WhyDetails(why: Why, today: LocalDate) {
    Column(
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val cutoff = why.cutoff
        val cutoffText = when (cutoff?.reason) {
            CutoffReason.RAIN -> stringResource(R.string.why_cutoff_rain, timeText(cutoff.time, today))
            CutoffReason.NIGHT -> stringResource(R.string.why_cutoff_night, timeText(cutoff.time, today))
            CutoffReason.NO_DATA -> stringResource(R.string.why_cutoff_no_data, timeText(cutoff.time, today))
            null -> stringResource(R.string.why_indoor)
        }
        Text(cutoffText, style = MaterialTheme.typography.bodyMedium)

        val conditions = why.conditions
        if (cutoff != null && conditions != null) {
            Text(stringResource(R.string.why_averages), style = MaterialTheme.typography.bodyMedium)
            WhyRow(R.string.why_humidity, stringResource(R.string.why_humidity_value, conditions.avgRhPercent.roundToInt()))
            WhyRow(R.string.why_wind, stringResource(R.string.why_wind_value, conditions.avgWindMs))
            WhyRow(
                R.string.why_sun,
                stringResource(R.string.why_sun_value, stringResource(sunLabel(conditions.avgRadiationWm2)), conditions.avgRadiationWm2.roundToInt()),
            )
        }
    }
}

@Composable
private fun WhyRow(label: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// Rough bands of shortwave radiation, so W/m² reads as something familiar.
private fun sunLabel(radiationWm2: Double): Int = when {
    radiationWm2 < 100 -> R.string.why_sun_overcast
    radiationWm2 < 300 -> R.string.why_sun_some
    radiationWm2 < 600 -> R.string.why_sun_sunny
    else -> R.string.why_sun_strong
}
