package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.rf.LinkBudgetExecutionMode
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetProvenance
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.theme.AtxAmber
import com.gecesars.atxplan.ui.theme.AtxSignal
import java.util.Locale

@Composable
fun StudiesScreen(
    project: PlannerProject?,
    resultInput: LinkBudgetInput?,
    result: LinkBudgetResult?,
    calculatorError: String?,
    isCalculating: Boolean,
    onCalculate: (LinkBudgetInput) -> Unit,
) {
    var frequency by rememberSaveable { mutableStateOf("900") }
    var distance by rememberSaveable { mutableStateOf("10") }
    var txPower by rememberSaveable { mutableStateOf("43") }
    var txGain by rememberSaveable { mutableStateOf("15") }
    var txLoss by rememberSaveable { mutableStateOf("2") }
    var rxGain by rememberSaveable { mutableStateOf("0") }
    var rxLoss by rememberSaveable { mutableStateOf("0") }
    var additionalLoss by rememberSaveable { mutableStateOf("0") }
    var sensitivity by rememberSaveable { mutableStateOf("-95") }
    var bandwidth by rememberSaveable { mutableStateOf("10") }
    var noiseFigure by rememberSaveable { mutableStateOf("6") }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val currentInput = linkBudgetInputOrNull(
        frequency = frequency,
        distance = distance,
        txPower = txPower,
        txGain = txGain,
        txLoss = txLoss,
        rxGain = rxGain,
        rxLoss = rxLoss,
        additionalLoss = additionalLoss,
        sensitivity = sensitivity,
        bandwidth = bandwidth,
        noiseFigure = noiseFigure,
    )
    val resultMatchesCurrentInput = result != null && resultInput == currentInput
    val currentProvenance = result?.takeIf { resultMatchesCurrentInput }?.provenance

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "Link Budget",
                subtitle = currentProvenance?.let { provenance ->
                    "${provenance.modelLabel} result with explicit terms and verifiable units."
                } ?: "Calculation provenance is recorded with every completed result.",
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentProvenance == null) {
                    StatusPill("Awaiting Calculation", StatusTone.INFO)
                } else {
                    StatusPill(currentProvenance.modelLabel, StatusTone.INFO)
                    StatusPill(
                        executionModeLabel(currentProvenance.executionMode),
                        if (currentProvenance.executionMode == LinkBudgetExecutionMode.LOCAL) {
                            StatusTone.POSITIVE
                        } else {
                            StatusTone.WARNING
                        },
                    )
                    StatusPill(currentProvenance.implementationLabel, StatusTone.INFO)
                }
            }
        }
        project?.let {
            item {
                Text(
                    "Workspace: ${it.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ParameterSection(title = "Path") {
                TwoFields(
                    first = {
                        NumericField("Frequency", "MHz", frequency, { frequency = it })
                    },
                    second = {
                        NumericField("Distance", "km", distance, { distance = it })
                    },
                )
                NumericField("Additional loss", "dB", additionalLoss, { additionalLoss = it })
            }
        }
        item {
            ParameterSection(title = "Transmitter") {
                TwoFields(
                    first = { NumericField("TX power", "dBm", txPower, { txPower = it }, signed = true) },
                    second = { NumericField("TX gain", "dBi", txGain, { txGain = it }, signed = true) },
                )
                NumericField("TX loss", "dB", txLoss, { txLoss = it })
            }
        }
        item {
            ParameterSection(title = "Receiver") {
                TwoFields(
                    first = { NumericField("RX gain", "dBi", rxGain, { rxGain = it }, signed = true) },
                    second = { NumericField("RX loss", "dB", rxLoss, { rxLoss = it }) },
                )
                TwoFields(
                    first = {
                        NumericField("Sensitivity", "dBm", sensitivity, { sensitivity = it }, signed = true)
                    },
                    second = {
                        NumericField("Noise figure", "dB", noiseFigure, { noiseFigure = it })
                    },
                )
                NumericField("Bandwidth", "MHz", bandwidth, { bandwidth = it })
            }
        }
        val effectiveError = formError ?: calculatorError
        if (effectiveError != null) {
            item { ErrorCard(effectiveError) }
        }
        if (result != null && !resultMatchesCurrentInput) {
            item { StaleResultCard() }
        }
        if (isCalculating) {
            item {
                Text(
                    text = "Calculating the current link budget.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Button(
                onClick = {
                    if (currentInput == null) {
                        formError = "Check the fields and enter decimal numbers only."
                    } else {
                        formError = null
                        onCalculate(currentInput)
                    }
                },
                enabled = !isCalculating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 15.dp),
            ) {
                Icon(Icons.Outlined.Calculate, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (isCalculating) "Calculating..." else "Calculate Link Budget")
            }
        }
        result?.takeIf { resultMatchesCurrentInput }?.let { linkResult ->
            item { ResultSection(linkResult) }
        }
        item { ProvenanceCard(currentProvenance) }
    }
}

@Composable
private fun ProvenanceCard(provenance: LinkBudgetProvenance?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Functions, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(
                    text = provenance?.let { "${it.modelLabel} Scope" } ?: "Calculation Provenance",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (provenance == null) {
                Text(
                    "Run a calculation to record its model, implementation, data sources, " +
                        "methodology, and limitations.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ProvenanceText("Model ID: ${provenance.modelId}")
                ProvenanceText(provenance.methodology)
                ProvenanceText(provenance.limitations)
                ProvenanceText("Implementation: ${provenance.implementationLabel}")
                ProvenanceText("Implementation ID: ${provenance.implementationId}")
                ProvenanceText("Data provenance: ${provenance.dataProvenance}")
            }
        }
    }
}

@Composable
private fun ProvenanceText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun executionModeLabel(mode: LinkBudgetExecutionMode): String = when (mode) {
    LinkBudgetExecutionMode.LOCAL -> "Local Calculation"
    LinkBudgetExecutionMode.REMOTE -> "Remote Calculation"
}

@Composable
private fun ParameterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TwoFields(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (largeText || maxWidth < 332.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first()
                second()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) { first() }
                Column(modifier = Modifier.weight(1f)) { second() }
            }
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    suffix: String,
    value: String,
    onValueChange: (String) -> Unit,
    signed: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            val allowed = candidate.filterIndexed { index, char ->
                char.isDigit() || char == ',' || char == '.' || (signed && char == '-' && index == 0)
            }
            onValueChange(allowed.take(14))
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (signed) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResultSection(result: LinkBudgetResult) {
    val metrics = listOf(
        ResultMetricData("Free-space path loss", result.freeSpacePathLossDb, "dB"),
        ResultMetricData("EIRP", result.eirpDbm, "dBm"),
        ResultMetricData("Received power", result.receivedPowerDbm, "dBm"),
        ResultMetricData(
            "Margin above sensitivity",
            result.fadeMarginDb,
            "dB",
            positive = result.fadeMarginDb >= 0.0,
        ),
        ResultMetricData("Midpoint Fresnel radius", result.firstFresnelMidpointRadiusM, "m"),
        ResultMetricData("Noise floor", result.noiseFloorDbm, "dBm"),
        ResultMetricData(
            "Thermal SNR",
            result.signalToNoiseDb,
            "dB",
            positive = result.signalToNoiseDb >= 0.0,
        ),
    )
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val useCompactGrid = !largeText && maxWidth >= 352.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Results", style = MaterialTheme.typography.titleLarge)
            if (useCompactGrid) {
                metrics.chunked(2).forEach { rowMetrics ->
                    if (rowMetrics.size == 1) {
                        ResultMetric(
                            metric = rowMetrics.single(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowMetrics.forEach { metric ->
                                ResultMetric(
                                    metric = metric,
                                    stacked = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else {
                metrics.forEach { metric -> ResultMetric(metric = metric) }
            }
        }
    }
}

private data class ResultMetricData(
    val label: String,
    val value: Double,
    val unit: String,
    val positive: Boolean? = null,
)

@Composable
private fun ResultMetric(
    metric: ResultMetricData,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = when (metric.positive) {
                true -> AtxSignal.copy(alpha = 0.12f)
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(metric.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = String.format(Locale.US, "%.2f %s", metric.value, metric.unit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(metric.label, modifier = Modifier.weight(1f))
                Text(
                    text = String.format(Locale.US, "%.2f %s", metric.value, metric.unit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Text(message, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StaleResultCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = "Inputs changed. The previous result is hidden until you calculate again.",
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun linkBudgetInputOrNull(
    frequency: String,
    distance: String,
    txPower: String,
    txGain: String,
    txLoss: String,
    rxGain: String,
    rxLoss: String,
    additionalLoss: String,
    sensitivity: String,
    bandwidth: String,
    noiseFigure: String,
): LinkBudgetInput? {
    val values = listOf(
        frequency,
        distance,
        txPower,
        txGain,
        txLoss,
        rxGain,
        rxLoss,
        additionalLoss,
        sensitivity,
        bandwidth,
        noiseFigure,
    ).map(::parseDecimal)
    if (values.any { it == null }) return null
    return LinkBudgetInput(
        frequencyMHz = values[0]!!,
        distanceKm = values[1]!!,
        transmitPowerDbm = values[2]!!,
        transmitAntennaGainDbi = values[3]!!,
        transmitLossDb = values[4]!!,
        receiveAntennaGainDbi = values[5]!!,
        receiveLossDb = values[6]!!,
        additionalPathLossDb = values[7]!!,
        receiverSensitivityDbm = values[8]!!,
        bandwidthMHz = values[9]!!,
        receiverNoiseFigureDb = values[10]!!,
    )
}

private fun parseDecimal(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()
