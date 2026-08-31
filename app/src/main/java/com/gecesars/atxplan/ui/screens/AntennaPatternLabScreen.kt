package com.gecesars.atxplan.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.ui.antenna.AntennaArraySynthesisRequest
import com.gecesars.atxplan.ui.antenna.AntennaArrayTaper
import com.gecesars.atxplan.ui.antenna.AntennaArrayTopology
import com.gecesars.atxplan.ui.antenna.AntennaArbitraryElementRequest
import com.gecesars.atxplan.ui.antenna.AntennaPrnValueInterpretation
import com.gecesars.atxplan.ui.antenna.AntennaPatternExportFormat
import com.gecesars.atxplan.ui.antenna.AntennaPatternExportPreview
import com.gecesars.atxplan.ui.antenna.AntennaPatternLabUiState
import com.gecesars.atxplan.ui.components.ScreenHeader
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class AntennaLabTab(val label: String) {
    LIBRARY("Library"),
    COMPOSER("Composer"),
    ASSIGNMENTS("Assignments"),
}

private data class ArbitraryElementDraft(
    val id: String = "element-1",
    val patternId: String = "",
    val xWavelengths: String = "0.0",
    val yWavelengths: String = "0.0",
    val zWavelengths: String = "0.0",
    val relativePower: String = "1.0",
    val feedPhaseDegrees: String = "0.0",
    val feedDelayNanoseconds: String = "0.0",
    val horizontalOrientationDegrees: String = "0.0",
    val elevationOrientationDegrees: String = "0.0",
    val rollDegrees: String = "0.0",
    val active: Boolean = true,
)

private val arbitraryElementDraftsSaver = listSaver<List<ArbitraryElementDraft>, String>(
    save = { drafts ->
        drafts.flatMap { draft ->
            listOf(
                draft.id,
                draft.patternId,
                draft.xWavelengths,
                draft.yWavelengths,
                draft.zWavelengths,
                draft.relativePower,
                draft.feedPhaseDegrees,
                draft.feedDelayNanoseconds,
                draft.horizontalOrientationDegrees,
                draft.elevationOrientationDegrees,
                draft.rollDegrees,
                draft.active.toString(),
            )
        }
    },
    restore = { values ->
        values.chunked(12).mapNotNull { fields ->
            fields.takeIf { it.size == 12 }?.let {
                ArbitraryElementDraft(
                    id = it[0],
                    patternId = it[1],
                    xWavelengths = it[2],
                    yWavelengths = it[3],
                    zWavelengths = it[4],
                    relativePower = it[5],
                    feedPhaseDegrees = it[6],
                    feedDelayNanoseconds = it[7],
                    horizontalOrientationDegrees = it[8],
                    elevationOrientationDegrees = it[9],
                    rollDegrees = it[10],
                    active = it[11].toBooleanStrictOrNull() ?: true,
                )
            }
        }.ifEmpty { listOf(ArbitraryElementDraft()) }
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AntennaPatternLabScreen(
    project: PlannerProject?,
    state: AntennaPatternLabUiState,
    isCatalogWritable: Boolean,
    onImportUri: (Uri) -> Unit,
    onImportPairUris: (List<Uri>) -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
    onResolvePrnConvention: (String, AntennaPrnValueInterpretation) -> Unit,
    onDismissPrnConvention: (String) -> Unit,
    onSynthesize: (AntennaArraySynthesisRequest) -> Unit,
    onPrepareExport: (String, AntennaPatternExportFormat) -> Unit,
    onExportUri: (String, AntennaPatternExportFormat, Uri) -> Unit,
    onDismissExport: (String) -> Unit,
    onAssignTransmitPattern: (String, Sector, String?) -> Unit,
    onDeletePattern: (AntennaPatternRecord) -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var destinationExportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var destinationExportFormatName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletePatternId by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImportUri) }
    val pairImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onImportPairUris(uris) }
    val onExportDestinationResult: (Uri?) -> Unit = { uri ->
        val token = destinationExportToken
        val format = destinationExportFormatName?.let { name ->
            AntennaPatternExportFormat.entries.firstOrNull { candidate -> candidate.name == name }
        }
        destinationExportToken = null
        destinationExportFormatName = null
        if (uri != null && token != null && format != null) {
            onExportUri(token, format, uri)
        } else {
            token?.let(onDismissExport)
        }
    }
    val nativeJsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AntennaPatternExportFormat.ATX_JSON.mediaType),
        onExportDestinationResult,
    )
    val desktopJsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AntennaPatternExportFormat.ATX_DESKTOP_JSON.mediaType),
        onExportDestinationResult,
    )
    val textExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AntennaPatternExportFormat.PRN.mediaType),
        onExportDestinationResult,
    )

    if (project == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(
                title = "Antenna Pattern Lab",
                subtitle = "The project is no longer available in the active catalog.",
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                Text("Back to RF Assets")
            }
        }
        return
    }

    val canWrite = isCatalogWritable && !state.isBusy
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("antenna_pattern_lab"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "Antenna Pattern Lab",
                subtitle = "Import, synthesize, inspect, assign, and export calculation-ready " +
                    "HRP/VRP patterns for ${project.name.take(80)}.",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "text/plain",
                                    "application/json",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        enabled = canWrite,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("import_pattern"),
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text("Single File", maxLines = 1)
                    }
                    Button(
                        onClick = {
                            pairImportLauncher.launch(
                                arrayOf("text/plain", "application/octet-stream"),
                            )
                        },
                        enabled = canWrite,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("import_pattern_pair"),
                    ) {
                        Text("HRP + VRP", maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = onBack,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    Text("RF Assets")
                }
            }
        }
        if (state.isBusy) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            state.operationLabel ?: "Processing antenna data",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        state.error?.let { message ->
            item { OperationMessage(message, error = true, onDismiss = onDismissMessage) }
        }
        state.notice?.let { message ->
            item { OperationMessage(message, error = false, onDismiss = onDismissMessage) }
        }
        stickyHeader {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 2.dp,
            ) {
                PrimaryTabRow(selectedTabIndex = tabIndex, modifier = Modifier.fillMaxWidth()) {
                    AntennaLabTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            text = { Text(tab.label, maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
        when (AntennaLabTab.entries[tabIndex]) {
            AntennaLabTab.LIBRARY -> {
                if (project.antennaPatterns.isEmpty()) {
                    item {
                        CompactInfo(
                            "No antenna patterns are stored. Import PRN, PAT, HRP/VRP, or ATX " +
                                "Antenna JSON, or synthesize an array from the Composer tab.",
                        )
                    }
                }
                items(project.antennaPatterns, key = AntennaPatternRecord::id) { pattern ->
                    PatternLibraryCard(
                        pattern = pattern,
                        enabled = canWrite,
                        onExport = { format ->
                            onPrepareExport(pattern.id, format)
                        },
                        onDelete = { pendingDeletePatternId = pattern.id },
                    )
                }
            }

            AntennaLabTab.COMPOSER -> item {
                AntennaComposerPanel(
                    patterns = project.antennaPatterns,
                    enabled = canWrite,
                    onSynthesize = onSynthesize,
                )
            }

            AntennaLabTab.ASSIGNMENTS -> {
                val sectors = project.sites.flatMap { site ->
                    site.sectors.map { sector -> Triple(site.id, site.name, sector) }
                }
                if (sectors.isEmpty()) item { CompactInfo("Add a transmitter sector before assigning a pattern.") }
                items(sectors, key = { (siteId, _, sector) -> "$siteId/${sector.id}" }) {
                        (siteId, siteName, sector) ->
                    PatternAssignmentCard(
                        siteId = siteId,
                        siteName = siteName,
                        sector = sector,
                        patterns = project.antennaPatterns,
                        enabled = canWrite,
                        onAssign = onAssignTransmitPattern,
                    )
                }
            }
        }
    }

    state.pendingPrnConventionChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = {
                if (!state.isBusy) onDismissPrnConvention(choice.token)
            },
            title = { Text("Choose PRN Value Meaning") },
            text = {
                Column(
                    modifier = Modifier.testTag("prn_convention_choice"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "This unmarked PRN uses values from 0 through 1, which can mean either " +
                            "positive field attenuation in dB or normalized linear field. " +
                            "The app cannot select safely without your confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Source: ${choice.sourceDisplayNames.joinToString(" + ")}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "Affected cut(s): ${choice.ambiguousPlaneLabels.joinToString()}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "Desktop positive attenuation treats 0 as peak field and larger values " +
                            "as more attenuation. Normalized linear E/Emax treats 1 as peak field.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (choice.sourceDisplayNames.size > 1) {
                        Text(
                            "This one selection applies to every ambiguous unmarked PRN in the " +
                                "pair. Cancel if the source files use different conventions.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            onResolvePrnConvention(
                                choice.token,
                                AntennaPrnValueInterpretation.DESKTOP_POSITIVE_ATTENUATION_DB,
                            )
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.testTag("prn_positive_attenuation"),
                    ) { Text("Desktop Positive Attenuation") }
                    TextButton(
                        onClick = {
                            onResolvePrnConvention(
                                choice.token,
                                AntennaPrnValueInterpretation.NORMALIZED_LINEAR_FIELD,
                            )
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.testTag("prn_normalized_linear"),
                    ) { Text("Normalized Linear E/Emax") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onDismissPrnConvention(choice.token) },
                    enabled = !state.isBusy,
                ) { Text("Cancel") }
            },
        )
    }
    state.pendingImport?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.isBusy) onDismissImport() },
            title = { Text("Review Antenna Import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(preview.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${preview.detectedFormat} · ${preview.sourceByteCount} bytes · " +
                            "SHA-256 ${preview.sourceSha256.take(12)}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (preview.componentDisplayNames.size > 1) {
                        Text(
                            "Preserved sources: ${preview.componentDisplayNames.joinToString(" + ")}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "HRP ${preview.horizontalSampleCount} samples · " +
                            "VRP ${preview.verticalSampleCount} samples",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    preview.nominalFrequencyHz?.let { frequencyHz ->
                        Text(
                            "Nominal frequency ${formatNumber(frequencyHz / 1_000_000.0, 3)} MHz",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    preview.peakGainDbi?.let { gain ->
                        Text(
                            "Peak gain ${formatNumber(gain, 2)} dBi",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (preview.warnings.isNotEmpty()) {
                        HorizontalDivider()
                        preview.warnings.take(6).forEach { warning ->
                            Text("• $warning", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        "Confirmation stores the bounded source and canonical normalized pattern " +
                            "as immutable project artifacts.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (!preview.isCalculationReady) {
                        Text(
                            "Review only: this file lacks an explicitly available HRP or VRP cut " +
                                "and cannot be stored, assigned, composed, or exported. Use " +
                                "HRP + VRP to review and pair two independent cuts.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmImport,
                    enabled = canWrite && preview.isCalculationReady,
                ) { Text("Import Pattern") }
            },
            dismissButton = {
                TextButton(onClick = onDismissImport, enabled = !state.isBusy) { Text("Cancel") }
            },
        )
    }
    state.pendingExport?.let { preview ->
        PreparedExportDialog(
            preview = preview,
            isBusy = state.isBusy || destinationExportToken != null,
            onChooseDestination = {
                destinationExportToken = preview.token
                destinationExportFormatName = preview.format.name
                when (preview.format) {
                    AntennaPatternExportFormat.ATX_JSON ->
                        nativeJsonExportLauncher.launch(preview.suggestedFileName)

                    AntennaPatternExportFormat.ATX_DESKTOP_JSON ->
                        desktopJsonExportLauncher.launch(preview.suggestedFileName)

                    AntennaPatternExportFormat.PRN,
                    AntennaPatternExportFormat.PAT,
                    AntennaPatternExportFormat.HRP,
                    AntennaPatternExportFormat.VRP,
                    AntennaPatternExportFormat.VSOFT_HRP,
                    AntennaPatternExportFormat.VSOFT_VRP,
                    -> textExportLauncher.launch(preview.suggestedFileName)
                }
            },
            onDismiss = { onDismissExport(preview.token) },
        )
    }
    pendingDeletePatternId?.let { patternId ->
        val pattern = project.antennaPatterns.firstOrNull { candidate -> candidate.id == patternId }
        if (pattern != null) {
            AlertDialog(
                onDismissRequest = { if (!state.isBusy) pendingDeletePatternId = null },
                title = { Text("Delete Antenna Pattern?") },
                text = {
                    Text(
                        "${pattern.name} and its project artifact references will be removed. " +
                            "Assigned patterns must be unassigned first.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeletePatternId = null
                            onDeletePattern(pattern)
                        },
                        enabled = canWrite,
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDeletePatternId = null },
                        enabled = !state.isBusy,
                    ) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun PreparedExportDialog(
    preview: AntennaPatternExportPreview,
    isBusy: Boolean,
    onChooseDestination: () -> Unit,
    onDismiss: () -> Unit,
) {
    var warningsExpanded by rememberSaveable(preview.token) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("Export Ready") },
        text = {
            Column(
                modifier = Modifier.testTag("export_preflight_dialog"),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(preview.format.label, style = MaterialTheme.typography.titleSmall)
                Text(preview.suggestedFileName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${preview.byteCount} bytes · SHA-256 ${preview.sha256.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Destination type: ${preview.mediaType}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "The canonical artifact and selected format passed validation before a " +
                        "destination was requested.",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (preview.warnings.isEmpty()) {
                    Text("No format-loss warnings.", style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(
                        onClick = { warningsExpanded = !warningsExpanded },
                        modifier = Modifier.testTag("export_warning_toggle"),
                    ) {
                        Text(
                            if (warningsExpanded) {
                                "Hide ${preview.warnings.size} warning(s)"
                            } else {
                                "Show all ${preview.warnings.size} warning(s)"
                            },
                        )
                    }
                    if (warningsExpanded) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(preview.warnings) { warning ->
                                Text("• $warning", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onChooseDestination,
                enabled = !isBusy,
                modifier = Modifier.testTag("choose_export_destination"),
            ) { Text("Choose Destination") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Cancel") }
        },
    )
}

@Composable
private fun PatternLibraryCard(
    pattern: AntennaPatternRecord,
    enabled: Boolean,
    onExport: (AntennaPatternExportFormat) -> Unit,
    onDelete: () -> Unit,
) {
    var exportExpanded by remember { mutableStateOf(false) }
    val calculationReady = pattern.hasVerifiedNormalizedContentIdentity()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pattern.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(pattern.origin.name.replace('_', ' '))
                            pattern.nominalFrequencyHz?.let { append(" · ${formatNumber(it / 1e6, 3)} MHz") }
                            pattern.peakGainDbi?.let { append(" · ${formatNumber(it, 2)} dBi") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Box {
                    TextButton(
                        onClick = { exportExpanded = true },
                        enabled = enabled && calculationReady,
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Text("Export")
                    }
                    DropdownMenu(
                        expanded = exportExpanded,
                        onDismissRequest = { exportExpanded = false },
                    ) {
                        AntennaPatternExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.label) },
                                enabled = calculationReady,
                                onClick = {
                                    exportExpanded = false
                                    onExport(format)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete antenna pattern")
                }
            }
            pattern.horizontalCut?.let { cut ->
                HorizontalPatternPlot(
                    values = cut.normalizedField,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
            Text(
                "${pattern.sourceFormat.ifBlank { "Unknown source" }} · " +
                    "HRP ${pattern.horizontalCut?.normalizedField?.size ?: 0} · " +
                    "VRP ${pattern.verticalCut?.normalizedField?.size ?: 0} · " +
                    "E/Emax",
                style = MaterialTheme.typography.labelSmall,
            )
            if (!calculationReady) {
                Text(
                    "Not calculation-ready: cut availability or the gain-bound content identity " +
                        "could not be verified.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            pattern.warnings.take(2).forEach { warning ->
                Text("• $warning", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HorizontalPatternPlot(
    values: List<Double>,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val patternColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val radius = min(size.width, size.height) * 0.45f
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(4) { ring ->
            drawCircle(
                color = gridColor,
                radius = radius * (ring + 1) / 4f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        drawLine(gridColor, center - Offset(radius, 0f), center + Offset(radius, 0f), 1.dp.toPx())
        drawLine(gridColor, center - Offset(0f, radius), center + Offset(0f, radius), 1.dp.toPx())
        if (values.size >= 3) {
            val path = Path()
            values.forEachIndexed { index, amplitude ->
                val angleRadians = Math.toRadians(index.toDouble() - 90.0)
                val point = Offset(
                    x = center.x + radius * amplitude.toFloat() * cos(angleRadians).toFloat(),
                    y = center.y + radius * amplitude.toFloat() * sin(angleRadians).toFloat(),
                )
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            path.close()
            drawPath(
                path = path,
                color = patternColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun AntennaComposerPanel(
    patterns: List<AntennaPatternRecord>,
    enabled: Boolean,
    onSynthesize: (AntennaArraySynthesisRequest) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("Synthesized Array") }
    var frequencyMHz by rememberSaveable { mutableStateOf("100.0") }
    var columns by rememberSaveable { mutableStateOf("2") }
    var rows by rememberSaveable { mutableStateOf("1") }
    var spacingX by rememberSaveable { mutableStateOf("0.5") }
    var spacingY by rememberSaveable { mutableStateOf("0.5") }
    var scanAzimuth by rememberSaveable { mutableStateOf("0.0") }
    var scanElevation by rememberSaveable { mutableStateOf("0.0") }
    var basePatternId by rememberSaveable { mutableStateOf<String?>(null) }
    var topologyName by rememberSaveable { mutableStateOf(AntennaArrayTopology.PLANAR.name) }
    var taperName by rememberSaveable { mutableStateOf(AntennaArrayTaper.UNIFORM.name) }
    var arbitraryElements by rememberSaveable(stateSaver = arbitraryElementDraftsSaver) {
        mutableStateOf(listOf(ArbitraryElementDraft()))
    }
    var selectedArbitraryElementIndex by rememberSaveable { mutableIntStateOf(0) }
    var baseExpanded by remember { mutableStateOf(false) }
    var topologyExpanded by remember { mutableStateOf(false) }
    var taperExpanded by remember { mutableStateOf(false) }
    val topology = AntennaArrayTopology.entries.firstOrNull { it.name == topologyName }
        ?: AntennaArrayTopology.PLANAR
    val request = parseSynthesisRequest(
        name = name,
        basePatternId = basePatternId,
        frequencyMHz = frequencyMHz,
        topologyName = topologyName,
        columns = columns,
        rows = rows,
        spacingX = spacingX,
        spacingY = spacingY,
        scanAzimuth = scanAzimuth,
        scanElevation = scanElevation,
        taperName = taperName,
        arbitraryElements = arbitraryElements,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SettingsInputAntenna, contentDescription = null)
                Text(
                    "Coherent Array Composer",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Physical aperture XY, boresight +Z. Power fractions become field amplitudes by " +
                    "square root before complex summation.",
                style = MaterialTheme.typography.labelSmall,
            )
            DenseField(name, { name = it }, "Pattern name", KeyboardType.Text)
            DenseField(frequencyMHz, { frequencyMHz = it }, "Frequency (MHz)", KeyboardType.Decimal)
            Box {
                OutlinedButton(
                    onClick = { topologyExpanded = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(topology.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(
                    expanded = topologyExpanded,
                    onDismissRequest = { topologyExpanded = false },
                ) {
                    AntennaArrayTopology.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                topologyName = option.name
                                topologyExpanded = false
                            },
                        )
                    }
                }
            }
            when (topology) {
                AntennaArrayTopology.SINGLE -> Unit
                AntennaArrayTopology.ARBITRARY -> {
                    ArbitraryElementEditor(
                        drafts = arbitraryElements,
                        selectedIndex = selectedArbitraryElementIndex,
                        patterns = patterns,
                        onSelect = { selectedArbitraryElementIndex = it },
                        onChange = { index, draft ->
                            arbitraryElements = arbitraryElements.toMutableList().also { items ->
                                items[index] = draft
                            }
                        },
                        onAdd = {
                            if (arbitraryElements.size < 512) {
                                val nextId = nextArbitraryElementId(arbitraryElements)
                                arbitraryElements = arbitraryElements + ArbitraryElementDraft(id = nextId)
                                selectedArbitraryElementIndex = arbitraryElements.lastIndex
                            }
                        },
                        onDuplicate = {
                            if (arbitraryElements.size < 512) {
                                val duplicate = arbitraryElements[selectedArbitraryElementIndex].copy(
                                    id = nextArbitraryElementId(arbitraryElements),
                                )
                                arbitraryElements = arbitraryElements + duplicate
                                selectedArbitraryElementIndex = arbitraryElements.lastIndex
                            }
                        },
                        onRemove = {
                            if (arbitraryElements.size > 1) {
                                arbitraryElements = arbitraryElements.filterIndexed { index, _ ->
                                    index != selectedArbitraryElementIndex
                                }
                                selectedArbitraryElementIndex = selectedArbitraryElementIndex
                                    .coerceAtMost(arbitraryElements.lastIndex)
                            }
                        },
                    )
                }
                AntennaArrayTopology.VERTICAL_STACK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DenseField(rows, { rows = it }, "Elements", KeyboardType.Number, Modifier.weight(1f))
                        DenseField(spacingY, { spacingY = it }, "Spacing (λ)", KeyboardType.Decimal, Modifier.weight(1f))
                    }
                }
                AntennaArrayTopology.HORIZONTAL_LINEAR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DenseField(columns, { columns = it }, "Elements", KeyboardType.Number, Modifier.weight(1f))
                        DenseField(spacingX, { spacingX = it }, "Spacing (λ)", KeyboardType.Decimal, Modifier.weight(1f))
                    }
                }
                AntennaArrayTopology.CIRCULAR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DenseField(columns, { columns = it }, "Elements", KeyboardType.Number, Modifier.weight(1f))
                        DenseField(spacingX, { spacingX = it }, "Radius (λ)", KeyboardType.Decimal, Modifier.weight(1f))
                    }
                }
                AntennaArrayTopology.PLANAR,
                AntennaArrayTopology.MULTIPANEL,
                -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DenseField(
                            columns,
                            { columns = it },
                            if (topology == AntennaArrayTopology.MULTIPANEL) "Panels" else "Columns",
                            KeyboardType.Number,
                            Modifier.weight(1f),
                        )
                        DenseField(
                            rows,
                            { rows = it },
                            if (topology == AntennaArrayTopology.MULTIPANEL) "Elements/panel" else "Rows",
                            KeyboardType.Number,
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DenseField(
                            spacingX,
                            { spacingX = it },
                            if (topology == AntennaArrayTopology.MULTIPANEL) "Tower radius (λ)" else "X spacing (λ)",
                            KeyboardType.Decimal,
                            Modifier.weight(1f),
                        )
                        DenseField(
                            spacingY,
                            { spacingY = it },
                            "Vertical spacing (λ)",
                            KeyboardType.Decimal,
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            if (topology != AntennaArrayTopology.ARBITRARY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DenseField(
                        scanAzimuth,
                        { scanAzimuth = it },
                        "H scan (deg)",
                        KeyboardType.Decimal,
                        Modifier.weight(1f),
                    )
                    DenseField(
                        scanElevation,
                        { scanElevation = it },
                        "V scan (deg)",
                        KeyboardType.Decimal,
                        Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { baseExpanded = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            patterns.firstOrNull { it.id == basePatternId }?.name ?: "Isotropic element",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = baseExpanded, onDismissRequest = { baseExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Isotropic element") },
                            onClick = { basePatternId = null; baseExpanded = false },
                        )
                        patterns.filter { pattern -> pattern.hasVerifiedNormalizedContentIdentity() }
                            .forEach { pattern ->
                            DropdownMenuItem(
                                text = { Text(pattern.name) },
                                onClick = { basePatternId = pattern.id; baseExpanded = false },
                            )
                        }
                    }
                }
                if (topology != AntennaArrayTopology.ARBITRARY) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { taperExpanded = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(taperName.lowercase().replaceFirstChar(Char::uppercase))
                        }
                        DropdownMenu(expanded = taperExpanded, onDismissRequest = { taperExpanded = false }) {
                            AntennaArrayTaper.entries.forEach { taper ->
                                DropdownMenuItem(
                                    text = { Text(taper.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                    onClick = { taperName = taper.name; taperExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
            if (request == null) {
                Text(
                    if (topology == AntennaArrayTopology.ARBITRARY) {
                        "Use 1–512 uniquely named elements, finite coordinates, valid orientation, and at least one active positive power weight."
                    } else {
                        "Use a name, positive frequency, 1–32 elements per dimension, no more than " +
                            "512 active elements, 0.05–5.0 λ spacing/radius, and scan angles from -60° to +60°."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { request?.let(onSynthesize) },
                enabled = enabled && request != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("synthesize_pattern"),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text("Synthesize and Store")
            }
        }
    }
}

@Composable
private fun ArbitraryElementEditor(
    drafts: List<ArbitraryElementDraft>,
    selectedIndex: Int,
    patterns: List<AntennaPatternRecord>,
    onSelect: (Int) -> Unit,
    onChange: (Int, ArbitraryElementDraft) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
) {
    val safeIndex = selectedIndex.coerceIn(0, drafts.lastIndex)
    val draft = drafts[safeIndex]
    val verifiedPatterns = patterns.filter { pattern ->
        pattern.hasVerifiedNormalizedContentIdentity()
    }
    var patternExpanded by remember { mutableStateOf(false) }
    val activeCount = drafts.count { it.active }
    val positivePower = drafts.sumOf { item ->
        if (item.active) item.relativePower.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0 else 0.0
    }

    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag("arbitrary_element_editor"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${drafts.size} elements · $activeCount active · ${formatNumber(positivePower, 3)} total weight",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onSelect(safeIndex - 1) },
                    enabled = safeIndex > 0,
                ) { Text("Previous") }
                Text(
                    "Element ${safeIndex + 1} of ${drafts.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = { onSelect(safeIndex + 1) },
                    enabled = safeIndex < drafts.lastIndex,
                ) { Text("Next") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onAdd,
                    enabled = drafts.size < 512,
                    modifier = Modifier.weight(1f).testTag("arbitrary_add_element"),
                ) { Text("Add") }
                TextButton(
                    onClick = onDuplicate,
                    enabled = drafts.size < 512,
                    modifier = Modifier.weight(1f).testTag("arbitrary_duplicate_element"),
                ) { Text("Duplicate") }
                TextButton(
                    onClick = onRemove,
                    enabled = drafts.size > 1,
                    modifier = Modifier.weight(1f).testTag("arbitrary_remove_element"),
                ) { Text("Remove") }
            }
            DenseField(
                value = draft.id,
                onValueChange = { onChange(safeIndex, draft.copy(id = it.take(80))) },
                label = "Element ID",
                keyboardType = KeyboardType.Text,
            )
            Box {
                OutlinedButton(
                    onClick = { patternExpanded = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                ) {
                    Text(
                        verifiedPatterns.firstOrNull { it.id == draft.patternId }?.name
                            ?: "Use array base pattern",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = patternExpanded,
                    onDismissRequest = { patternExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Use array base pattern") },
                        onClick = {
                            onChange(safeIndex, draft.copy(patternId = ""))
                            patternExpanded = false
                        },
                    )
                    verifiedPatterns.forEach { pattern ->
                        DropdownMenuItem(
                            text = { Text(pattern.name) },
                            onClick = {
                                onChange(safeIndex, draft.copy(patternId = pattern.id))
                                patternExpanded = false
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DenseField(
                    draft.xWavelengths,
                    { onChange(safeIndex, draft.copy(xWavelengths = it)) },
                    "X (λ)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                DenseField(
                    draft.yWavelengths,
                    { onChange(safeIndex, draft.copy(yWavelengths = it)) },
                    "Y (λ)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                DenseField(
                    draft.zWavelengths,
                    { onChange(safeIndex, draft.copy(zWavelengths = it)) },
                    "Z (λ)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DenseField(
                    draft.relativePower,
                    { onChange(safeIndex, draft.copy(relativePower = it)) },
                    "Power weight",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                DenseField(
                    draft.feedPhaseDegrees,
                    { onChange(safeIndex, draft.copy(feedPhaseDegrees = it)) },
                    "Phase (deg)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                DenseField(
                    draft.feedDelayNanoseconds,
                    { onChange(safeIndex, draft.copy(feedDelayNanoseconds = it)) },
                    "Delay (ns)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DenseField(
                    draft.horizontalOrientationDegrees,
                    { onChange(safeIndex, draft.copy(horizontalOrientationDegrees = it)) },
                    "Azimuth (deg)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                DenseField(
                    draft.elevationOrientationDegrees,
                    { onChange(safeIndex, draft.copy(elevationOrientationDegrees = it)) },
                    "Elevation (deg)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
                DenseField(
                    draft.rollDegrees,
                    { onChange(safeIndex, draft.copy(rollDegrees = it)) },
                    "Roll (deg)",
                    KeyboardType.Decimal,
                    Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = { onChange(safeIndex, draft.copy(active = !draft.active)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("arbitrary_toggle_active"),
            ) {
                Text(if (draft.active) "Active element" else "Inactive element")
            }
            Text(
                "Coordinates use wavelengths at the array frequency. Feed delay is converted to phase as −360 · f · delay. Active weights are normalized to total power 1.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun nextArbitraryElementId(drafts: List<ArbitraryElementDraft>): String {
    val existing = drafts.mapTo(mutableSetOf()) { it.id }
    var suffix = drafts.size + 1
    while ("element-$suffix" in existing) suffix += 1
    return "element-$suffix"
}

@Composable
private fun PatternAssignmentCard(
    siteId: String,
    siteName: String,
    sector: Sector,
    patterns: List<AntennaPatternRecord>,
    enabled: Boolean,
    onAssign: (String, Sector, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val assigned = patterns.firstOrNull { pattern -> pattern.id == sector.transmitAntennaPatternId }
    val assignedCalculationReady = assigned?.hasVerifiedNormalizedContentIdentity() ?: true
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("$siteName · ${sector.name}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Az ${formatNumber(sector.azimuthDegrees, 1)}° · " +
                        "${formatNumber(sector.frequencyMHz, 3)} MHz",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (!assignedCalculationReady) {
                    Text(
                        "Assigned pattern rejected: nominal omnidirectional fallback will be used.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 46.dp),
                ) {
                    Text(assigned?.name ?: "Omnidirectional", maxLines = 1)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("No assigned pattern") },
                        onClick = {
                            expanded = false
                            onAssign(siteId, sector, null)
                        },
                    )
                    patterns.filter { pattern -> pattern.hasVerifiedNormalizedContentIdentity() }
                        .forEach { pattern ->
                        DropdownMenuItem(
                            text = { Text(pattern.name) },
                            onClick = {
                                expanded = false
                                onAssign(siteId, sector, pattern.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DenseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate -> if (candidate.length <= 80) onValueChange(candidate) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

@Composable
private fun CompactInfo(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(message, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OperationMessage(
    message: String,
    error: Boolean,
    onDismiss: () -> Unit,
) {
    val hasDetails = '\n' in message
    var detailsExpanded by rememberSaveable(message) { mutableStateOf(false) }
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    if (detailsExpanded) message else message.substringBefore('\n'),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hasDetails) {
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        Text(if (detailsExpanded) "Hide details" else "Show details")
                    }
                }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun parseSynthesisRequest(
    name: String,
    basePatternId: String?,
    frequencyMHz: String,
    topologyName: String,
    columns: String,
    rows: String,
    spacingX: String,
    spacingY: String,
    scanAzimuth: String,
    scanElevation: String,
    taperName: String,
    arbitraryElements: List<ArbitraryElementDraft> = emptyList(),
): AntennaArraySynthesisRequest? {
    val cleanName = name.trim().takeIf { it.length in 2..160 } ?: return null
    val frequency = frequencyMHz.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val topology = AntennaArrayTopology.entries.firstOrNull { it.name == topologyName } ?: return null
    val taper = AntennaArrayTaper.entries.firstOrNull { it.name == taperName } ?: return null
    if (topology == AntennaArrayTopology.ARBITRARY) {
        val elements = parseArbitraryElements(arbitraryElements) ?: return null
        return AntennaArraySynthesisRequest(
            name = cleanName,
            basePatternId = basePatternId,
            frequencyMHz = frequency,
            topology = topology,
            columns = 1,
            rows = 1,
            horizontalSpacingWavelengths = 0.5,
            verticalSpacingWavelengths = 0.5,
            horizontalScanDegrees = 0.0,
            verticalScanDegrees = 0.0,
            taper = taper,
            arbitraryElements = elements,
        )
    }
    val columnCount = columns.toIntOrNull()?.takeIf { it in 1..32 } ?: return null
    val rowCount = rows.toIntOrNull()?.takeIf { it in 1..32 } ?: return null
    val elementCount = when (topology) {
        AntennaArrayTopology.SINGLE -> 1
        AntennaArrayTopology.VERTICAL_STACK -> rowCount
        AntennaArrayTopology.HORIZONTAL_LINEAR,
        AntennaArrayTopology.CIRCULAR,
        -> columnCount
        AntennaArrayTopology.PLANAR,
        AntennaArrayTopology.MULTIPANEL,
        -> columnCount * rowCount
        AntennaArrayTopology.ARBITRARY -> return null
    }
    if (elementCount > 512) return null
    val horizontalSpacing = spacingX.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.05..5.0 }
        ?: return null
    val verticalSpacing = spacingY.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.05..5.0 }
        ?: return null
    val horizontalScan = scanAzimuth.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..60.0 }
        ?: return null
    val verticalScan = scanElevation.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..60.0 }
        ?: return null
    return AntennaArraySynthesisRequest(
        name = cleanName,
        basePatternId = basePatternId,
        frequencyMHz = frequency,
        topology = topology,
        columns = columnCount,
        rows = rowCount,
        horizontalSpacingWavelengths = horizontalSpacing,
        verticalSpacingWavelengths = verticalSpacing,
        horizontalScanDegrees = horizontalScan,
        verticalScanDegrees = verticalScan,
        taper = taper,
    )
}

private fun parseArbitraryElements(
    drafts: List<ArbitraryElementDraft>,
): List<AntennaArbitraryElementRequest>? {
    if (drafts.size !in 1..512) return null
    val cleanIds = drafts.map { draft -> draft.id.trim() }
    if (
        cleanIds.distinct().size != cleanIds.size ||
        cleanIds.any { id -> id.length !in 1..80 || id.any(Char::isISOControl) }
    ) return null
    val parsed = drafts.mapIndexed { index, draft ->
        fun boundedDouble(value: String, range: ClosedFloatingPointRange<Double>): Double? =
            value.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }

        AntennaArbitraryElementRequest(
            id = cleanIds[index],
            patternId = draft.patternId.ifBlank { null },
            xWavelengths = boundedDouble(draft.xWavelengths, -10_000.0..10_000.0)
                ?: return null,
            yWavelengths = boundedDouble(draft.yWavelengths, -10_000.0..10_000.0)
                ?: return null,
            zWavelengths = boundedDouble(draft.zWavelengths, -10_000.0..10_000.0)
                ?: return null,
            relativePower = boundedDouble(draft.relativePower, 0.0..1.0e12) ?: return null,
            feedPhaseDegrees = boundedDouble(draft.feedPhaseDegrees, -1.0e6..1.0e6)
                ?: return null,
            feedDelayNanoseconds = boundedDouble(draft.feedDelayNanoseconds, -1.0e6..1.0e6)
                ?: return null,
            horizontalOrientationDegrees = boundedDouble(
                draft.horizontalOrientationDegrees,
                0.0..359.999_999_999,
            ) ?: return null,
            elevationOrientationDegrees = boundedDouble(
                draft.elevationOrientationDegrees,
                -90.0..90.0,
            ) ?: return null,
            rollDegrees = boundedDouble(draft.rollDegrees, -180.0..180.0) ?: return null,
            active = draft.active,
        )
    }
    val activePower = parsed.sumOf { element ->
        if (element.active) element.relativePower else 0.0
    }
    return parsed.takeIf { activePower.isFinite() && activePower > 0.0 }
}

private fun formatNumber(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
