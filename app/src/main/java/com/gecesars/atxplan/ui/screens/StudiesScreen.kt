package com.gecesars.atxplan.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gecesars.atxplan.domain.application.RunProjectLinkStudyCommand
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.rf.LinkBudgetExecutionMode
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetProvenance
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import com.gecesars.atxplan.domain.study.ProjectLinkStudyRecord
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.theme.AtxAmber
import com.gecesars.atxplan.ui.theme.AtxSignal
import java.util.Locale
import java.util.UUID

@Composable
fun StudiesScreen(
    project: PlannerProject?,
    resultInput: LinkBudgetInput?,
    result: LinkBudgetResult?,
    calculatorError: String?,
    isCalculating: Boolean,
    isRunningProjectLinkStudy: Boolean,
    canSaveProjectStudy: Boolean,
    onCalculate: (LinkBudgetInput) -> Unit,
    onRunProjectLinkStudy: (RunProjectLinkStudyCommand) -> Unit,
    brazilDigitalTvStudy: BrazilDigitalTvRegulatoryStudyResult? = null,
    brazilDigitalTvStudyError: String? = null,
    isRunningBrazilDigitalTvStudy: Boolean = false,
    brazilDigitalTvStudyProgress: Pair<Int, Int>? = null,
    onRunBrazilDigitalTvStudy: (Double) -> Unit = {},
    onExportBrazilDigitalTvReport: (Uri) -> Unit = {},
    onExportBrazilDigitalTvPdf: (Uri) -> Unit = {},
    onExportBrazilDigitalTvXlsx: (Uri) -> Unit = {},
    onExportBrazilDigitalTvKmz: (Uri) -> Unit = {},
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
    var showOlderSavedStudies by rememberSaveable(project?.id) { mutableStateOf(false) }
    val orderedProjectStudies = project?.linkStudies
        ?.sortedWith(
            compareByDescending<ProjectLinkStudyRecord> { it.createdAtEpochMillis }
                .thenByDescending { it.id },
        )
        .orEmpty()
    val olderProjectStudies = orderedProjectStudies.drop(1)
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("studies_list"),
        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "Link Studies",
                subtitle = "Use stored project endpoints or run the independent manual calculator.",
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentProvenance == null) {
                    StatusPill("${project?.linkStudies?.size ?: 0} Saved", StatusTone.INFO)
                    StatusPill("Manual Ready", StatusTone.INFO)
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
            BrazilDigitalTvRegulatoryStudyCard(
                project = project,
                result = brazilDigitalTvStudy,
                error = brazilDigitalTvStudyError,
                isRunning = isRunningBrazilDigitalTvStudy,
                progress = brazilDigitalTvStudyProgress,
                onRun = onRunBrazilDigitalTvStudy,
                onExportReport = onExportBrazilDigitalTvReport,
                onExportPdf = onExportBrazilDigitalTvPdf,
                onExportXlsx = onExportBrazilDigitalTvXlsx,
                onExportKmz = onExportBrazilDigitalTvKmz,
            )
        }
        item {
            ProjectLinkStudyComposer(
                project = project,
                canSave = canSaveProjectStudy,
                isRunning = isRunningProjectLinkStudy,
                onRun = onRunProjectLinkStudy,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Manual Calculator", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Manual inputs and their current result remain in memory and are not added to the project.",
                    style = MaterialTheme.typography.bodySmall,
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
        if (olderProjectStudies.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = { showOlderSavedStudies = !showOlderSavedStudies },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("saved_study_history_toggle"),
                ) {
                    Text(
                        if (showOlderSavedStudies) {
                            "Hide Older Saved Studies"
                        } else {
                            "Show ${olderProjectStudies.size} Older Saved " +
                                if (olderProjectStudies.size == 1) "Study" else "Studies"
                        },
                    )
                }
            }
            if (showOlderSavedStudies) {
                items(
                    items = olderProjectStudies,
                    key = { study -> "saved-history-${study.id}" },
                ) { study ->
                    SavedProjectStudy(study)
                }
            }
        }
    }
}

@Composable
private fun BrazilDigitalTvRegulatoryStudyCard(
    project: PlannerProject?,
    result: BrazilDigitalTvRegulatoryStudyResult?,
    error: String?,
    isRunning: Boolean,
    progress: Pair<Int, Int>?,
    onRun: (Double) -> Unit,
    onExportReport: (Uri) -> Unit,
    onExportPdf: (Uri) -> Unit,
    onExportXlsx: (Uri) -> Unit,
    onExportKmz: (Uri) -> Unit,
) {
    var radiusText by rememberSaveable(project?.id) { mutableStateOf("30") }
    var localError by rememberSaveable(project?.id) { mutableStateOf<String?>(null) }
    val reportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { uri -> uri?.let(onExportReport) }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let(onExportPdf) }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ) { uri -> uri?.let(onExportXlsx) }
    val kmzLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kmz"),
    ) { uri -> uri?.let(onExportKmz) }
    val radius = radiusText.toDoubleOrNull()
    val validRadius = radius != null &&
        radius in 1.0..100.0

    Card(
        modifier = Modifier.fillMaxWidth().testTag("brazil_dtv_regulatory_study"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Brazil Digital TV Regulatory Study", style = MaterialTheme.typography.titleMedium)
            Text(
                "The active project transmitter is authoritative. Anatel Basic Plan channels are external references only and never populate project fields.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill("P.1546-6 protected", StatusTone.INFO)
                StatusPill("P.526-15 Deygout–Assis", StatusTone.INFO)
                StatusPill("E(50,90)", StatusTone.INFO)
                StatusPill("SP Basic Plan ±1 channel", StatusTone.INFO)
            }
            OutlinedTextField(
                value = radiusText,
                onValueChange = { value -> radiusText = value.take(8) },
                label = { Text("Study radius") },
                suffix = { Text("km") },
                singleLine = true,
                isError = radiusText.isNotBlank() && !validRadius,
                supportingText = if (radiusText.isNotBlank() && !validRadius) {
                    { Text("Enter a radius from 1 to 100 km.") }
                } else {
                    { Text("Protected-contour search boundary; 30 km for the current São Paulo study.") }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth().testTag("brazil_dtv_radius"),
            )
            if (project == null) {
                InlineNotice("Create or select an independent project before running this study.")
            }
            if (isRunning) {
                val completed = progress?.first ?: 0
                val total = progress?.second?.coerceAtLeast(1) ?: 1
                LinearProgressIndicator(
                    progress = { (completed.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Reading terrain and evaluating regulatory paths · $completed / $total",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val displayedError = localError ?: error
            displayedError?.let { ErrorCard(it) }
            Button(
                onClick = {
                    if (!validRadius) {
                        localError = "Enter a study radius from 1 to 100 km."
                    } else {
                        localError = null
                        onRun(checkNotNull(radius))
                    }
                },
                enabled = project != null && validRadius && !isRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("run_brazil_dtv_study"),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.Calculate, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (isRunning) "Calculating..." else "Run Regulatory Study")
            }
            result?.let { study ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(
                        if (study.filingReady) "Gates Passed" else "Not Filing-ready",
                        if (study.filingReady) StatusTone.POSITIVE else StatusTone.WARNING,
                    )
                    StatusPill("Channel ${study.channel}", StatusTone.INFO)
                    StatusPill("${study.radialEvidence.size} Radials", StatusTone.INFO)
                    StatusPill("${study.referenceStationCount} References", StatusTone.INFO)
                }
                Text(
                    "Protected contour: ${study.contour.status.name} · " +
                        "${study.duAssessments.count { it.failingPointCount > 0 }} D/U reference failure(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                study.blockers.forEach { blocker ->
                    Text(
                        "• $blocker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { reportLauncher.launch("atx-plan-channel-${study.channel}-regulatory-report.html") },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("export_brazil_dtv_report"),
                    ) {
                        Text("HTML", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { pdfLauncher.launch("atx-plan-channel-${study.channel}-regulatory-report.pdf") },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("export_brazil_dtv_pdf"),
                    ) {
                        Text("PDF", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { xlsxLauncher.launch("atx-plan-channel-${study.channel}-engineering-data.xlsx") },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("export_brazil_dtv_xlsx"),
                    ) {
                        Text("XLSX", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { kmzLauncher.launch("atx-plan-channel-${study.channel}-protected-contour.kmz") },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("export_brazil_dtv_kmz"),
                    ) {
                        Text("KMZ", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "Fingerprint ${study.inputFingerprint.take(16)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class SectorChoice(
    val site: RadioSite,
    val sector: Sector,
) {
    val key: String = "${site.id.length}:${site.id}${sector.id.length}:${sector.id}"
    val label: String = "${site.name} / ${sector.name}"
}

@Composable
private fun ProjectLinkStudyComposer(
    project: PlannerProject?,
    canSave: Boolean,
    isRunning: Boolean,
    onRun: (RunProjectLinkStudyCommand) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("project_link_study"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Project-linked P.525 Study", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select stored endpoints. Effective RF values and the completed result are snapshotted, fingerprinted, and saved locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (project == null) {
                Text(
                    "Create or select an active project before running a project-linked study.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProjectStudyLimits()
                return@Column
            }

            val sectorChoices = project.sites.flatMap { site ->
                site.sectors.map { sector -> SectorChoice(site, sector) }
            }
            var selectedSectorKey by rememberSaveable(project.id) { mutableStateOf("") }
            var selectedReceiverId by rememberSaveable(project.id) { mutableStateOf("") }
            var studyName by rememberSaveable(project.id) { mutableStateOf("") }
            val selectedSector = sectorChoices.firstOrNull { it.key == selectedSectorKey }
                ?: sectorChoices.singleOrNull()
            val networkId = selectedSector?.sector?.networkId
            val compatibleReceivers = if (networkId == null) {
                emptyList()
            } else {
                project.receivers.filter { receiver -> receiver.supportsNetwork(networkId) }
            }
            val selectedReceiver = compatibleReceivers.firstOrNull { it.id == selectedReceiverId }
                ?: compatibleReceivers.singleOrNull()

            CompactDropdown(
                label = "Transmitter sector",
                selectedLabel = selectedSector?.label ?: "Select a site and sector",
                entries = sectorChoices.map { it.key to it.label },
                enabled = sectorChoices.isNotEmpty() && !isRunning,
                testTag = "project_sector_selector",
                onSelect = { key ->
                    selectedSectorKey = key
                    selectedReceiverId = ""
                    studyName = ""
                },
            )
            if (sectorChoices.isEmpty()) {
                InlineNotice("No project sectors are available. Add an RF path or sector first.")
            } else if (selectedSector != null && networkId == null) {
                InlineNotice("The selected sector has no network reference and cannot run a project study.")
            }

            CompactDropdown(
                label = "Compatible receiver",
                selectedLabel = selectedReceiver?.name ?: "Select a receiver",
                entries = compatibleReceivers.map { it.id to it.name },
                enabled = selectedSector != null && compatibleReceivers.isNotEmpty() && !isRunning,
                testTag = "project_receiver_selector",
                onSelect = { id ->
                    selectedReceiverId = id
                    studyName = ""
                },
            )
            if (selectedSector != null && networkId != null && compatibleReceivers.isEmpty()) {
                InlineNotice("No receiver supports the selected sector network.")
            }

            if (selectedSector != null && selectedReceiver != null) {
                val defaultName = "${selectedSector.sector.name} to ${selectedReceiver.name}"
                val effectiveStudyName = studyName.trim().ifEmpty { defaultName }.take(80)
                val invalidStudyName = effectiveStudyName.length !in 2..80
                OutlinedTextField(
                    value = studyName,
                    onValueChange = { studyName = it.take(80) },
                    label = { Text("Study name") },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    isError = invalidStudyName,
                    supportingText = if (invalidStudyName) {
                        { Text("Use a study name between 2 and 80 characters.") }
                    } else {
                        null
                    },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth().testTag("project_study_name"),
                )
                ProjectEndpointSummary(project, selectedSector, selectedReceiver)
                Button(
                    onClick = {
                        onRun(
                            RunProjectLinkStudyCommand(
                                requestId = UUID.randomUUID().toString(),
                                expectedProject = project,
                                name = effectiveStudyName,
                                siteId = selectedSector.site.id,
                                sectorId = selectedSector.sector.id,
                                receiverId = selectedReceiver.id,
                            ),
                        )
                    },
                    enabled = canSave && !isRunning && !invalidStudyName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("run_project_link_study"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.Calculate, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (isRunning) "Calculating and Saving..." else "Calculate and Save Study")
                }
                if (!canSave && !isRunning) {
                    Text(
                        "Project storage is currently read-only or busy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ProjectStudyLimits()
            project.linkStudies.maxWithOrNull(
                compareBy<ProjectLinkStudyRecord> { it.createdAtEpochMillis }.thenBy { it.id },
            )?.let { latestStudy ->
                Text("Latest Saved Study", style = MaterialTheme.typography.titleSmall)
                SavedProjectStudy(latestStudy)
                if (project.linkStudies.size > 1) {
                    Text(
                        "Older saved studies remain available below the manual calculator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactDropdown(
    label: String,
    selectedLabel: String,
    entries: List<Pair<String, String>>,
    enabled: Boolean,
    testTag: String,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(label) { mutableStateOf("") }
    val filteredEntries = entries.filter { (_, entryLabel) ->
        query.isBlank() || entryLabel.contains(query.trim(), ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag),
        ) {
            Text(selectedLabel, maxLines = 2, modifier = Modifier.weight(1f))
        }
        if (expanded) {
            Dialog(
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Select $label", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(120) },
                            label = { Text("Search $label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        ) {
                            items(filteredEntries, key = { (id, _) -> id }) { (id, entryLabel) ->
                                DropdownMenuItem(
                                    text = { Text(entryLabel) },
                                    onClick = {
                                        expanded = false
                                        query = ""
                                        onSelect(id)
                                    },
                                )
                            }
                        }
                        if (filteredEntries.isEmpty()) {
                            Text(
                                "No stored option matches this search.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                expanded = false
                                query = ""
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectEndpointSummary(
    project: PlannerProject,
    transmitter: SectorChoice,
    receiver: Receiver,
) {
    val network = project.networks.firstOrNull { it.id == transmitter.sector.networkId }
    val compatibilityProfile = receiver.networkProfiles.firstOrNull { it.networkId == network?.id }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("project_endpoint_summary"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompactValue("Network", network?.name ?: "Missing reference")
        CompactValue(
            "TX coordinate",
            formatCoordinatePair(transmitter.site.location.latitude, transmitter.site.location.longitude),
        )
        CompactValue(
            "RX coordinate",
            formatCoordinatePair(
                receiver.location.latitude.value,
                receiver.location.longitude.value,
            ),
        )
        CompactValue(
            "Endpoint geometry",
            String.format(
                Locale.US,
                "TX %.2f m AGL at %.2f° azimuth / %.2f° tilt · RX %.2f m AGL",
                transmitter.sector.antennaHeightM,
                transmitter.sector.azimuthDegrees,
                transmitter.sector.electricalTiltDegrees,
                receiver.antennaHeightM.value,
            ),
        )
        CompactValue(
            "Stored TX ground",
            transmitter.site.groundElevationM?.let { elevation ->
                String.format(Locale.US, "%.2f m (not evaluated)", elevation)
            } ?: "NoData",
        )
        CompactValue(
            "RF chain",
            String.format(
                Locale.US,
                "%.3f MHz · %.2f dBm TX · %.2f/%.2f dBi gain · %.2f/%.2f dB loss",
                transmitter.sector.frequencyMHz,
                transmitter.sector.transmitPowerDbm,
                transmitter.sector.antennaGainDbi,
                compatibilityProfile?.antennaGainDbi ?: receiver.antennaGainDbi.value,
                transmitter.sector.feederLossDb,
                compatibilityProfile?.systemLossDb ?: receiver.systemLossDb.value,
            ),
        )
        CompactValue(
            "Receiver",
            String.format(
                Locale.US,
                "%.2f dBm sensitivity · %.3f MHz bandwidth · %.2f dB NF",
                compatibilityProfile?.sensitivityDbm ?: receiver.sensitivityDbm.value,
                network?.bandwidthMHz ?: Double.NaN,
                receiver.noiseFigureDb.value,
            ),
        )
        CompactValue(
            "Compatibility",
            when {
                compatibilityProfile == null -> "primary receiver network"
                compatibilityProfile.antennaGainDbi != null ||
                    compatibilityProfile.systemLossDb != null ||
                    compatibilityProfile.sensitivityDbm != null ->
                    "network profile; available receive-chain overrides will be applied"
                else -> "network profile; compatibility only, with no overrides supplied"
            },
        )
        CompactValue(
            "Source state",
            listOf(
                if (transmitter.sector.active) "active sector" else "inactive sector",
                if (network?.active == true) "active network" else "inactive network",
                if (transmitter.sector.transmitAntennaPatternId == null) {
                    "no TX pattern reference"
                } else {
                    "TX pattern referenced but not evaluated"
                },
            ).joinToString(" · "),
        )
        if (
            network != null &&
            kotlin.math.abs(transmitter.sector.frequencyMHz - network.downlinkFrequencyMHz) > 1e-9
        ) {
            CompactValue(
                "Frequency note",
                String.format(
                    Locale.US,
                    "Sector %.3f MHz is used; network downlink is %.3f MHz",
                    transmitter.sector.frequencyMHz,
                    network.downlinkFrequencyMHz,
                ),
            )
        }
    }
}

@Composable
private fun CompactValue(label: String, value: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackValue = label.length >= 16 ||
            maxWidth < 280.dp ||
            LocalDensity.current.fontScale >= 1.5f
        if (stackValue) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "$label:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "$label:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InlineNotice(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProjectStudyLimits() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Mean-Earth great-circle distance: ${String.format(Locale.US, "%,.1f", com.gecesars.atxplan.domain.study.EARTH_MEAN_RADIUS_M)} m radius.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Terrain profile is NoData and stored site elevation is not evaluated. No Earth-curvature clearance, effective-Earth propagation, LOS, Fresnel clearance, diffraction, clutter, buildings, vegetation, atmospheric gas, rain, variability, or antenna-pattern attenuation is calculated.",
            style = MaterialTheme.typography.bodySmall,
            color = AtxAmber,
        )
    }
}

@Composable
private fun SavedProjectStudy(study: ProjectLinkStudyRecord) {
    var showDetails by rememberSaveable(study.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("saved_project_study_${study.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(study.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${study.input.transmitter.siteName} / ${study.input.transmitter.sectorName} → ${study.input.receiver.receiverName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SavedMetric("Inclined path", study.geometry.inclinedDistanceM / 1_000.0, "km")
                SavedMetric("Initial bearing", study.geometry.initialBearingDegrees, "°")
                SavedMetric("FSPL", study.result.freeSpacePathLossDb, "dB")
                SavedMetric("RX", study.result.receivedPowerDbm, "dBm")
                SavedMetric("Margin", study.result.fadeMarginDb, "dB")
                SavedMetric("SNR", study.result.signalToNoiseDb, "dB")
            }
            Text(
                "${study.result.provenance.modelLabel} · ${study.geometry.geodesyId} · fingerprint ${study.inputFingerprintSha256.take(12)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showDetails = !showDetails },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("saved_project_study_details_${study.id}"),
            ) {
                Text(if (showDetails) "Hide Complete Details" else "Show Complete Details")
            }
            if (showDetails) {
                Text("Source Snapshot", style = MaterialTheme.typography.labelLarge)
                CompactValue(
                    "Source project",
                    "${study.input.projectName} (${study.input.projectId})",
                )
                CompactValue(
                    "Network",
                    "${study.input.networkName} (${study.input.networkId})",
                )
                CompactValue(
                    "TX source IDs",
                    "${study.input.transmitter.siteId} / ${study.input.transmitter.sectorId}",
                )
                CompactValue("RX source ID", study.input.receiver.receiverId)
                CompactValue(
                    "TX coordinate",
                    formatCoordinatePair(
                        study.input.transmitter.location.latitudeDegrees,
                        study.input.transmitter.location.longitudeDegrees,
                    ),
                )
                CompactValue(
                    "RX coordinate",
                    formatCoordinatePair(
                        study.input.receiver.location.latitudeDegrees,
                        study.input.receiver.location.longitudeDegrees,
                    ),
                )
                CompactValue(
                    "Endpoint heights",
                    String.format(
                        Locale.US,
                        "TX %.3f m AGL · RX %.3f m AGL · Δh %.3f m",
                        study.input.transmitter.antennaHeightAglM,
                        study.input.receiver.antennaHeightAglM,
                        study.geometry.heightDeltaM,
                    ),
                )
                CompactValue(
                    "Stored TX ground",
                    study.input.transmitter.storedSiteGroundElevationM?.let { elevation ->
                        String.format(Locale.US, "%.3f m (not evaluated)", elevation)
                    } ?: "NoData",
                )
                CompactValue(
                    "Source state",
                    listOf(
                        if (study.input.transmitter.sectorActive) "active sector" else "inactive sector",
                        if (study.input.networkActive) "active network" else "inactive network",
                        if (study.input.transmitter.directionalPatternReferenced) {
                            "TX pattern referenced but not evaluated"
                        } else {
                            "no TX pattern reference"
                        },
                    ).joinToString(" · "),
                )
                CompactValue(
                    "Receiver profile",
                    when {
                        study.input.receiverCompatibilityOverridesApplied ->
                            "compatibility declared; available overrides applied"
                        study.input.receiverCompatibilityProfilePresent ->
                            "compatibility declared; no overrides supplied"
                        else -> "primary receiver network"
                    },
                )

                Text("Geometry", style = MaterialTheme.typography.labelLarge)
                CompactValue(
                    "Distances",
                    String.format(
                        Locale.US,
                        "horizontal %.6f km · inclined %.6f km",
                        study.geometry.horizontalDistanceM / 1_000.0,
                        study.geometry.inclinedDistanceM / 1_000.0,
                    ),
                )
                CompactValue(
                    "Angles",
                    String.format(
                        Locale.US,
                        "initial %.6f° · relative %.6f° · elevation %.6f°",
                        study.geometry.initialBearingDegrees,
                        study.geometry.relativeAzimuthDegrees,
                        study.geometry.elevationAngleDegrees,
                    ),
                )
                CompactValue(
                    "Sector pointing",
                    String.format(
                        Locale.US,
                        "azimuth %.6f° · electrical tilt %.6f°",
                        study.input.transmitter.sectorAzimuthDegrees,
                        study.input.transmitter.electricalTiltDegrees,
                    ),
                )
                CompactValue(
                    "Mean-Earth radius",
                    String.format(Locale.US, "%.1f m", study.geometry.earthMeanRadiusM),
                )

                Text("Effective RF Input", style = MaterialTheme.typography.labelLarge)
                CompactValue(
                    "Frequency / distance",
                    String.format(
                        Locale.US,
                        "%.6f MHz / %.9f km",
                        study.input.linkBudget.frequencyMHz,
                        study.input.linkBudget.distanceKm,
                    ),
                )
                CompactValue(
                    "Network baseline",
                    String.format(
                        Locale.US,
                        "%.6f MHz downlink · %.6f MHz bandwidth",
                        study.input.networkDownlinkFrequencyMHz,
                        study.input.networkBandwidthMHz,
                    ),
                )
                CompactValue(
                    "TX chain",
                    String.format(
                        Locale.US,
                        "%.6f dBm power · %.6f dBi gain · %.6f dB loss",
                        study.input.linkBudget.transmitPowerDbm,
                        study.input.linkBudget.transmitAntennaGainDbi,
                        study.input.linkBudget.transmitLossDb,
                    ),
                )
                CompactValue(
                    "RX chain",
                    String.format(
                        Locale.US,
                        "%.6f dBi gain · %.6f dB loss · %.6f dBm sensitivity · %.6f dB NF",
                        study.input.linkBudget.receiveAntennaGainDbi,
                        study.input.linkBudget.receiveLossDb,
                        study.input.linkBudget.receiverSensitivityDbm,
                        study.input.linkBudget.receiverNoiseFigureDb,
                    ),
                )
                CompactValue(
                    "Additional path loss",
                    String.format(Locale.US, "%.6f dB", study.input.linkBudget.additionalPathLossDb),
                )

                Text("Complete Scalar Result", style = MaterialTheme.typography.labelLarge)
                CompactValue(
                    "Power",
                    String.format(
                        Locale.US,
                        "EIRP %.6f dBm · RX %.6f dBm · margin %.6f dB",
                        study.result.eirpDbm,
                        study.result.receivedPowerDbm,
                        study.result.fadeMarginDb,
                    ),
                )
                CompactValue(
                    "Noise",
                    String.format(
                        Locale.US,
                        "floor %.6f dBm · SNR %.6f dB",
                        study.result.noiseFloorDbm,
                        study.result.signalToNoiseDb,
                    ),
                )
                CompactValue(
                    "P.525 / Fresnel",
                    String.format(
                        Locale.US,
                        "FSPL %.6f dB · midpoint F1 %.6f m",
                        study.result.freeSpacePathLossDb,
                        study.result.firstFresnelMidpointRadiusM,
                    ),
                )

                Text("Identity and Provenance", style = MaterialTheme.typography.labelLarge)
                CompactValue("Study record ID", study.id)
                CompactValue("Created epoch ms", study.createdAtEpochMillis.toString())
                CompactValue("Study engine", study.engineId)
                CompactValue("Terrain state", "NoData")
                CompactValue("Model", study.result.provenance.modelLabel)
                CompactValue("Model ID", study.result.provenance.modelId)
                CompactValue(
                    "RF implementation",
                    study.result.provenance.implementationLabel,
                )
                CompactValue("RF implementation ID", study.result.provenance.implementationId)
                CompactValue(
                    "Execution mode",
                    executionModeLabel(study.result.provenance.executionMode),
                )
                CompactValue("Data provenance", study.result.provenance.dataProvenance)
                CompactValue("Geodesy", study.geometry.geodesyId)
                CompactValue("Model edition", study.result.provenance.modelEdition)
                CompactValue("Methodology", study.result.provenance.methodology)
                CompactValue("Limitations", study.result.provenance.limitations)
                CompactValue("Input fingerprint SHA-256", study.inputFingerprintSha256)
                study.result.provenance.referenceUrl?.let { reference ->
                    CompactValue("Reference", reference)
                }
            }
            study.warnings.forEach { warning ->
                Text(
                    "• $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = AtxAmber,
                )
            }
        }
    }
}

@Composable
private fun SavedMetric(label: String, value: Double, unit: String) {
    Text(
        String.format(Locale.US, "%s %.2f %s", label, value, unit),
        style = MaterialTheme.typography.labelMedium,
    )
}

private fun Receiver.supportsNetwork(networkId: String): Boolean =
    this.networkId == networkId || networkProfiles.any { it.networkId == networkId }

private fun formatCoordinatePair(latitude: Double, longitude: Double): String =
    String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

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
                provenance.modelEdition.takeIf(String::isNotBlank)?.let { edition ->
                    ProvenanceText("Edition: $edition")
                }
                ProvenanceText(provenance.methodology)
                ProvenanceText(provenance.limitations)
                ProvenanceText("Implementation: ${provenance.implementationLabel}")
                ProvenanceText("Implementation ID: ${provenance.implementationId}")
                ProvenanceText("Data provenance: ${provenance.dataProvenance}")
                provenance.referenceUrl?.let { reference ->
                    ProvenanceText("Reference: $reference")
                }
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
