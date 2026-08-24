package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.application.AddRfPathCommand
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.forms.RfPathDraft

@Composable
fun RfPathEditorScreen(
    project: PlannerProject?,
    isLoadingCatalog: Boolean,
    isSaving: Boolean,
    onSave: (AddRfPathCommand) -> Unit,
    onDirtyStateChange: (Boolean) -> Unit,
    onSavePendingChange: (Boolean) -> Unit,
    onSaveSucceeded: () -> Unit,
    onBack: () -> Unit,
) {
    if (project == null && isLoadingCatalog) {
        LoadingProjectContent()
        return
    }
    if (project == null) {
        MissingProjectContent(onBack)
        return
    }

    var draft by rememberSaveable(project.id, stateSaver = RfPathDraftSaver) {
        mutableStateOf(RfPathDraft())
    }
    var formError by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingSaveRevision by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var observedSaveInProgress by rememberSaveable(project.id) { mutableStateOf(false) }
    val projectRevision = project.rfPathRevision
    val isDirty = draft != RfPathDraft()

    LaunchedEffect(project.id, isDirty) {
        onDirtyStateChange(isDirty)
    }
    LaunchedEffect(project.id, pendingSaveRevision) {
        onSavePendingChange(pendingSaveRevision != null)
    }
    LaunchedEffect(projectRevision, isSaving, pendingSaveRevision) {
        val baselineRevision = pendingSaveRevision ?: return@LaunchedEffect
        when {
            projectRevision != baselineRevision -> {
                pendingSaveRevision = null
                observedSaveInProgress = false
                draft = RfPathDraft()
                formError = null
                onDirtyStateChange(false)
                onSavePendingChange(false)
                onSaveSucceeded()
            }
            isSaving -> observedSaveInProgress = true
            observedSaveInProgress -> {
                pendingSaveRevision = null
                observedSaveInProgress = false
                onSavePendingChange(false)
            }
        }
    }
    DisposableEffect(project.id) {
        onDispose {
            onDirtyStateChange(false)
            onSavePendingChange(false)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("rf_path_editor_list")
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "Add RF Path",
                subtitle = "Create a linked network, transmitter site, sector, and receiver in " +
                    "${project.name}.",
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    text = "All values below are explicit inputs. Saving performs one durable " +
                        "catalog change; no terrain or propagation result is inferred.",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        item {
            EditorSection("Network") {
                TextField(
                    label = "Network name",
                    value = draft.networkName,
                    onValueChange = { draft = draft.copy(networkName = it.take(80)) },
                    modifier = Modifier.testTag("network_name_field"),
                )
                RadioSystemField(
                    value = draft.radioSystem,
                    onValueChange = { draft = draft.copy(radioSystem = it) },
                )
                NumericField(
                    label = "Downlink frequency",
                    unit = "MHz",
                    value = draft.frequencyMHz,
                    onValueChange = { draft = draft.copy(frequencyMHz = it) },
                )
                NumericField(
                    label = "Bandwidth",
                    unit = "MHz",
                    value = draft.bandwidthMHz,
                    onValueChange = { draft = draft.copy(bandwidthMHz = it) },
                )
            }
        }
        item {
            EditorSection("Transmitter Site") {
                TextField(
                    label = "Site name",
                    value = draft.siteName,
                    onValueChange = { draft = draft.copy(siteName = it.take(80)) },
                )
                NumericField(
                    label = "Site latitude",
                    unit = "degrees",
                    value = draft.siteLatitude,
                    signed = true,
                    onValueChange = { draft = draft.copy(siteLatitude = it) },
                )
                NumericField(
                    label = "Site longitude",
                    unit = "degrees",
                    value = draft.siteLongitude,
                    signed = true,
                    onValueChange = { draft = draft.copy(siteLongitude = it) },
                )
                NotesField(
                    label = "Site notes (optional)",
                    value = draft.siteNotes,
                    onValueChange = { draft = draft.copy(siteNotes = it.take(2_000)) },
                )
            }
        }
        item {
            EditorSection("Transmitter Sector") {
                TextField(
                    label = "Sector name",
                    value = draft.sectorName,
                    onValueChange = { draft = draft.copy(sectorName = it.take(80)) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = draft.sectorActive,
                            role = Role.Switch,
                            onValueChange = {
                                draft = draft.copy(sectorActive = it)
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active sector", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Inactive sectors remain stored but are excluded from map rays.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.sectorActive,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                NumericField(
                    label = "Azimuth",
                    unit = "degrees true",
                    value = draft.sectorAzimuthDegrees,
                    onValueChange = { draft = draft.copy(sectorAzimuthDegrees = it) },
                )
                NumericField(
                    label = "Electrical tilt",
                    unit = "degrees",
                    value = draft.sectorTiltDegrees,
                    signed = true,
                    onValueChange = { draft = draft.copy(sectorTiltDegrees = it) },
                )
                NumericField(
                    label = "Antenna height",
                    unit = "m",
                    value = draft.sectorHeightM,
                    onValueChange = { draft = draft.copy(sectorHeightM = it) },
                )
                NumericField(
                    label = "Transmit power",
                    unit = "dBm",
                    value = draft.transmitPowerDbm,
                    signed = true,
                    onValueChange = { draft = draft.copy(transmitPowerDbm = it) },
                )
                NumericField(
                    label = "Antenna gain",
                    unit = "dBi",
                    value = draft.transmitGainDbi,
                    signed = true,
                    onValueChange = { draft = draft.copy(transmitGainDbi = it) },
                )
                NumericField(
                    label = "Feeder loss",
                    unit = "dB",
                    value = draft.feederLossDb,
                    onValueChange = { draft = draft.copy(feederLossDb = it) },
                )
            }
        }
        item {
            EditorSection("Receiver") {
                TextField(
                    label = "Receiver name",
                    value = draft.receiverName,
                    onValueChange = { draft = draft.copy(receiverName = it.take(80)) },
                )
                NumericField(
                    label = "Receiver latitude",
                    unit = "degrees",
                    value = draft.receiverLatitude,
                    signed = true,
                    onValueChange = { draft = draft.copy(receiverLatitude = it) },
                )
                NumericField(
                    label = "Receiver longitude",
                    unit = "degrees",
                    value = draft.receiverLongitude,
                    signed = true,
                    onValueChange = { draft = draft.copy(receiverLongitude = it) },
                )
                NumericField(
                    label = "Antenna height",
                    unit = "m",
                    value = draft.receiverHeightM,
                    onValueChange = { draft = draft.copy(receiverHeightM = it) },
                )
                NumericField(
                    label = "Antenna gain",
                    unit = "dBi",
                    value = draft.receiverGainDbi,
                    signed = true,
                    onValueChange = { draft = draft.copy(receiverGainDbi = it) },
                )
                NumericField(
                    label = "System loss",
                    unit = "dB",
                    value = draft.receiverSystemLossDb,
                    onValueChange = { draft = draft.copy(receiverSystemLossDb = it) },
                )
                NumericField(
                    label = "Sensitivity",
                    unit = "dBm",
                    value = draft.receiverSensitivityDbm,
                    signed = true,
                    onValueChange = { draft = draft.copy(receiverSensitivityDbm = it) },
                )
                NumericField(
                    label = "Noise figure",
                    unit = "dB",
                    value = draft.receiverNoiseFigureDb,
                    onValueChange = { draft = draft.copy(receiverNoiseFigureDb = it) },
                )
                NumericField(
                    label = "Azimuth",
                    unit = "degrees true",
                    value = draft.receiverAzimuthDegrees,
                    onValueChange = { draft = draft.copy(receiverAzimuthDegrees = it) },
                )
                NumericField(
                    label = "Electrical tilt",
                    unit = "degrees",
                    value = draft.receiverTiltDegrees,
                    signed = true,
                    onValueChange = { draft = draft.copy(receiverTiltDegrees = it) },
                )
                NotesField(
                    label = "Receiver notes (optional)",
                    value = draft.receiverNotes,
                    onValueChange = { draft = draft.copy(receiverNotes = it.take(2_000)) },
                )
            }
        }
        formError?.let { message ->
            item { FormErrorCard(message) }
        }
        item {
            Button(
                onClick = {
                    draft.toCommand(project.id)
                        .onSuccess { command ->
                            formError = null
                            pendingSaveRevision = projectRevision
                            observedSaveInProgress = false
                            onSavePendingChange(true)
                            onSave(command)
                        }
                        .onFailure { error ->
                            formError = error.message ?: "Check the RF path values and try again."
                        }
                },
                enabled = !isSaving && pendingSaveRevision == null,
                modifier = Modifier.fillMaxWidth().testTag("save_rf_path_button"),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 15.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(
                    if (isSaving || pendingSaveRevision != null) {
                        "Saving RF Path..."
                    } else {
                        "Save RF Path Locally"
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            content()
        }
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotesField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumericField(
    label: String,
    unit: String,
    value: String,
    signed: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            onValueChange(
                candidate.filterIndexed { index, character ->
                    character.isDigit() || character == '.' || character == ',' ||
                        (signed && character == '-' && index == 0)
                }.take(18),
            )
        },
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioSystemField(
    value: RadioSystem,
    onValueChange: (RadioSystem) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Radio system") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RadioSystem.entries.forEach { system ->
                DropdownMenuItem(
                    text = { Text(system.label) },
                    onClick = {
                        onValueChange(system)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FormErrorCard(message: String) {
    Card(
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Text(message, modifier = Modifier.weight(1f))
        }
    }
}

private val PlannerProject.rfPathRevision: String
    get() = "$updatedAtEpochMillis:${networks.size}:${sites.size}:${receivers.size}"

@Composable
private fun MissingProjectContent(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "Project Unavailable",
            subtitle = "The RF path editor could not resolve this project from local storage.",
        )
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text("Return to Projects")
        }
    }
}

@Composable
private fun LoadingProjectContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = "Opening RF Path Editor",
            subtitle = "Resolving the project from validated local storage.",
        )
        CircularProgressIndicator()
    }
}

private val RadioSystem.label: String
    get() = when (this) {
        RadioSystem.GENERIC -> "Generic System"
        RadioSystem.FM_BROADCAST -> "FM Broadcast"
        RadioSystem.TV_BROADCAST -> "TV Broadcast"
        RadioSystem.LTE -> "LTE"
        RadioSystem.NR_5G -> "5G NR"
        RadioSystem.LAND_MOBILE -> "Land Mobile Radio"
        RadioSystem.FWA -> "Fixed Wireless Access"
        RadioSystem.AIR_TO_GROUND -> "Air-to-Ground"
    }

private val RfPathDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "networkName" to draft.networkName,
            "radioSystem" to draft.radioSystem.name,
            "frequencyMHz" to draft.frequencyMHz,
            "bandwidthMHz" to draft.bandwidthMHz,
            "siteName" to draft.siteName,
            "siteLatitude" to draft.siteLatitude,
            "siteLongitude" to draft.siteLongitude,
            "siteNotes" to draft.siteNotes,
            "sectorName" to draft.sectorName,
            "sectorActive" to draft.sectorActive,
            "sectorAzimuthDegrees" to draft.sectorAzimuthDegrees,
            "sectorTiltDegrees" to draft.sectorTiltDegrees,
            "sectorHeightM" to draft.sectorHeightM,
            "transmitPowerDbm" to draft.transmitPowerDbm,
            "transmitGainDbi" to draft.transmitGainDbi,
            "feederLossDb" to draft.feederLossDb,
            "receiverName" to draft.receiverName,
            "receiverLatitude" to draft.receiverLatitude,
            "receiverLongitude" to draft.receiverLongitude,
            "receiverHeightM" to draft.receiverHeightM,
            "receiverGainDbi" to draft.receiverGainDbi,
            "receiverSystemLossDb" to draft.receiverSystemLossDb,
            "receiverSensitivityDbm" to draft.receiverSensitivityDbm,
            "receiverNoiseFigureDb" to draft.receiverNoiseFigureDb,
            "receiverAzimuthDegrees" to draft.receiverAzimuthDegrees,
            "receiverTiltDegrees" to draft.receiverTiltDegrees,
            "receiverNotes" to draft.receiverNotes,
        )
    },
    restore = { values ->
        val fallback = RfPathDraft()
        fun text(key: String, default: String) = values[key] as? String ?: default
        fun flag(key: String, default: Boolean) = values[key] as? Boolean ?: default
        val restoredSystem = (values["radioSystem"] as? String)
            ?.let { name -> RadioSystem.entries.firstOrNull { it.name == name } }
            ?: fallback.radioSystem
        RfPathDraft(
            networkName = text("networkName", fallback.networkName),
            radioSystem = restoredSystem,
            frequencyMHz = text("frequencyMHz", fallback.frequencyMHz),
            bandwidthMHz = text("bandwidthMHz", fallback.bandwidthMHz),
            siteName = text("siteName", fallback.siteName),
            siteLatitude = text("siteLatitude", fallback.siteLatitude),
            siteLongitude = text("siteLongitude", fallback.siteLongitude),
            siteNotes = text("siteNotes", fallback.siteNotes),
            sectorName = text("sectorName", fallback.sectorName),
            sectorActive = flag("sectorActive", fallback.sectorActive),
            sectorAzimuthDegrees = text(
                "sectorAzimuthDegrees",
                fallback.sectorAzimuthDegrees,
            ),
            sectorTiltDegrees = text("sectorTiltDegrees", fallback.sectorTiltDegrees),
            sectorHeightM = text("sectorHeightM", fallback.sectorHeightM),
            transmitPowerDbm = text("transmitPowerDbm", fallback.transmitPowerDbm),
            transmitGainDbi = text("transmitGainDbi", fallback.transmitGainDbi),
            feederLossDb = text("feederLossDb", fallback.feederLossDb),
            receiverName = text("receiverName", fallback.receiverName),
            receiverLatitude = text("receiverLatitude", fallback.receiverLatitude),
            receiverLongitude = text("receiverLongitude", fallback.receiverLongitude),
            receiverHeightM = text("receiverHeightM", fallback.receiverHeightM),
            receiverGainDbi = text("receiverGainDbi", fallback.receiverGainDbi),
            receiverSystemLossDb = text(
                "receiverSystemLossDb",
                fallback.receiverSystemLossDb,
            ),
            receiverSensitivityDbm = text(
                "receiverSensitivityDbm",
                fallback.receiverSensitivityDbm,
            ),
            receiverNoiseFigureDb = text(
                "receiverNoiseFigureDb",
                fallback.receiverNoiseFigureDb,
            ),
            receiverAzimuthDegrees = text(
                "receiverAzimuthDegrees",
                fallback.receiverAzimuthDegrees,
            ),
            receiverTiltDegrees = text("receiverTiltDegrees", fallback.receiverTiltDegrees),
            receiverNotes = text("receiverNotes", fallback.receiverNotes),
        )
    },
)
