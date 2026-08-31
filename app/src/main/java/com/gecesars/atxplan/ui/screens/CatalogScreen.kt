package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.domain.dataset.RegionalDatasetCatalog
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadResult
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalProcessingState
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.anatel.AnatelBasicPlanUiState
import com.gecesars.atxplan.ui.dataset.DataCatalogUiState
import com.gecesars.atxplan.ui.dataset.IbgeCatalogStatus
import com.gecesars.atxplan.ui.dataset.RegionalCoordinateField
import com.gecesars.atxplan.ui.dataset.RegionalDataUiPhase
import com.gecesars.atxplan.ui.dataset.RegionalDataUiState
import java.util.Locale

private data class Capability(
    val area: String,
    val capability: String,
    val stage: String,
    val tone: StatusTone,
    val detail: String,
)

private val capabilities = listOf(
    Capability(
        "Project",
        "Versioned local catalog",
        "Foundation delivered",
        StatusTone.POSITIVE,
        "Typed JSON, a defensive size limit, and atomic writes to private storage.",
    ),
    Capability(
        "Engineering",
        "FSPL, EIRP, noise, and Fresnel clearance",
        "Baseline delivered",
        StatusTone.POSITIVE,
        "Deterministic local calculations; unimplemented terms remain explicit.",
    ),
    Capability(
        "GIS",
        "Engineering site and azimuth map",
        "Foundation delivered",
        StatusTone.INFO,
        "Offline coordinate grid with stale-safe site-coordinate editing; a cartographic renderer, authorized map packages, and full GIS editing remain planned.",
    ),
    Capability(
        "Interoperability",
        ".atxp / RadioPlanner import",
        "Planned",
        StatusTone.WARNING,
        "Requires a stable contract, defensive ZIP reading, migrations, and legally usable fixtures.",
    ),
    Capability(
        "Data",
        "Regional DSM, land cover, and buildings",
        "Acquisition foundation delivered",
        StatusTone.INFO,
        "User-bounded downloads add hashes, provenance, licenses, resumable GET partials, a disk budget, GeoTIFF processing, and experimental OSM building processing. Supported Copernicus float32 tiles can supply DSM elevations to the digital-TV study; bare-earth DTM, clutter loss, and building-height interpretation remain unavailable.",
    ),
    Capability(
        "Propagation",
        "ITM, P.1812, P.1546, and P.528",
        "Numerical gate",
        StatusTone.NEGATIVE,
        "Only after selecting an NDK/backend strategy, standards editions, and parity tolerances.",
    ),
    Capability(
        "Studies",
        "Coverage, interference, and best server",
        "Planned",
        StatusTone.WARNING,
        "Start with small, cancellable grids; large areas will use resumable jobs.",
    ),
    Capability(
        "Brazil",
        "Anatel and IBGE offline catalogs",
        "Bounded slices delivered",
        StatusTone.INFO,
        "The verified IBGE 2022 attribute index is bundled. Anatel TV/FM channels use an " +
            "explicit, review-gated snapshot download and atomic offline index. Project linking, " +
            "sector polygons, population-by-coverage, and regulatory conclusions remain planned.",
    ),
)

@Composable
fun CatalogScreen(
    state: DataCatalogUiState = DataCatalogUiState(),
    regionalState: RegionalDataUiState = RegionalDataUiState(),
    anatelState: AnatelBasicPlanUiState = AnatelBasicPlanUiState(),
    onMunicipalityQueryChange: (String) -> Unit = {},
    onMunicipalitySelected: (String) -> Unit = {},
    onRetryDataset: () -> Unit = {},
    onRegionalCoordinateChange: (RegionalCoordinateField, String) -> Unit = { _, _ -> },
    onRegionalSelectionToggle: (RegionalDatasetSelection) -> Unit = {},
    onRegionalLiveSnapshotRefreshChange: (Boolean) -> Unit = {},
    onReviewRegionalPlan: () -> Unit = {},
    onRegionalLicensesAccepted: (Boolean) -> Unit = {},
    onStartRegionalAcquisition: () -> Unit = {},
    onCancelRegionalAcquisition: () -> Unit = {},
    onEditRegionalRequest: () -> Unit = {},
    onAnatelLicenseReviewAcknowledged: (Boolean) -> Unit = {},
    onRefreshAnatelCatalog: () -> Unit = {},
    onAnatelServiceSelected: (AnatelBroadcastService) -> Unit = {},
    onAnatelQueryTextChange: (String) -> Unit = {},
    onAnatelStateCodeChange: (String) -> Unit = {},
    onAnatelChannelChange: (String) -> Unit = {},
    onSearchAnatelCatalog: () -> Unit = {},
    onLoadPreviousAnatelRecords: () -> Unit = {},
    onLoadMoreAnatelRecords: () -> Unit = {},
    onDismissAnatelMessage: () -> Unit = {},
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useTwoColumns = !largeText && maxWidth >= 600.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .testTag("catalog_list"),
            contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Data & Capabilities",
                    subtitle = "A clear inventory of what works now and the gates for each engine.",
                )
            }
            item {
                if (useTwoColumns) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        PrincipleCard(
                            icon = Icons.Outlined.Security,
                            title = "No Silent Substitution",
                            body = "Missing data, an unavailable runtime, or an invalid file must produce a diagnostic—not an apparently valid result.",
                            modifier = Modifier.weight(1f),
                        )
                        PrincipleCard(
                            icon = Icons.Outlined.CloudDownload,
                            title = "Regional by Default",
                            body = "Large rasters and geometry remain regional. The national IBGE attribute index is the bounded exception, packaged with explicit hashes, provenance, and limitations.",
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrincipleCard(
                            icon = Icons.Outlined.Security,
                            title = "No Silent Substitution",
                            body = "Missing data, an unavailable runtime, or an invalid file must produce a diagnostic—not an apparently valid result.",
                        )
                        PrincipleCard(
                            icon = Icons.Outlined.CloudDownload,
                            title = "Regional by Default",
                            body = "Large rasters and geometry remain regional. The national IBGE attribute index is the bounded exception, packaged with explicit hashes, provenance, and limitations.",
                        )
                    }
                }
            }
            item {
                RegionalDataHeader(regionalState.phase)
            }
            item {
                RegionalRequestCard(
                    state = regionalState,
                    onCoordinateChange = onRegionalCoordinateChange,
                    onSelectionToggle = onRegionalSelectionToggle,
                    onLiveSnapshotRefreshChange = onRegionalLiveSnapshotRefreshChange,
                    onReview = onReviewRegionalPlan,
                )
            }
            if (regionalState.phase == RegionalDataUiPhase.REVIEW && regionalState.plan != null) {
                item {
                    RegionalPlanReviewCard(
                        state = regionalState,
                        onLicensesAccepted = onRegionalLicensesAccepted,
                        onStart = onStartRegionalAcquisition,
                        onEdit = onEditRegionalRequest,
                    )
                }
            }
            if (regionalState.phase == RegionalDataUiPhase.RUNNING) {
                item {
                    RegionalProgressCard(
                        state = regionalState,
                        onCancel = onCancelRegionalAcquisition,
                    )
                }
            }
            if (
                regionalState.phase == RegionalDataUiPhase.COMPLETE ||
                regionalState.phase == RegionalDataUiPhase.FAILED ||
                regionalState.phase == RegionalDataUiPhase.CANCELLED
            ) {
                item {
                    RegionalResultCard(
                        state = regionalState,
                        onEdit = onEditRegionalRequest,
                    )
                }
            }
            item {
                RegionalInventoryCard(regionalState)
            }
            item {
                RegionalReadinessNotice()
            }
            item {
                AnatelBasicPlanCatalogSection(
                    state = anatelState,
                    onLicenseReviewAcknowledged = onAnatelLicenseReviewAcknowledged,
                    onRefresh = onRefreshAnatelCatalog,
                    onServiceSelected = onAnatelServiceSelected,
                    onQueryTextChange = onAnatelQueryTextChange,
                    onStateCodeChange = onAnatelStateCodeChange,
                    onChannelChange = onAnatelChannelChange,
                    onSearch = onSearchAnatelCatalog,
                    onLoadPrevious = onLoadPreviousAnatelRecords,
                    onLoadMore = onLoadMoreAnatelRecords,
                    onDismissMessage = onDismissAnatelMessage,
                )
            }
            item {
                IbgeDatasetHeader(state.ibgeStatus)
            }
            item {
                IbgeDatasetStatusCard(
                    state = state,
                    onRetry = onRetryDataset,
                )
            }
            if (state.ibgeStatus == IbgeCatalogStatus.READY) {
                item {
                    OutlinedTextField(
                        value = state.municipalityQuery,
                        onValueChange = onMunicipalityQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ibge_municipality_search"),
                        label = { Text("Municipality name or 7-digit IBGE code") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.isSearchingMunicipalities) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).testTag("ibge_search_progress"),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        supportingText = {
                            Text(
                                if (state.municipalityQuery.isBlank()) {
                                    "Showing the largest municipalities by 2022 resident population."
                                } else {
                                    "Search is normalized locally; no network request is made."
                                },
                            )
                        },
                        singleLine = true,
                    )
                }
                state.searchErrorMessage?.let { message ->
                    item {
                        InlineDatasetMessage(message, error = true)
                    }
                }
                if (
                    !state.isSearchingMunicipalities &&
                    state.searchErrorMessage == null &&
                    state.municipalityResults.isEmpty()
                ) {
                    item {
                        InlineDatasetMessage(
                            message = "No recognized municipality matches this local query.",
                            error = false,
                        )
                    }
                }
                items(
                    items = state.municipalityResults,
                    key = { municipality -> "ibge-municipality-${municipality.code}" },
                ) { municipality ->
                    MunicipalityResultCard(
                        municipality = municipality,
                        selected = state.selectedMunicipality?.code == municipality.code,
                        onSelect = { onMunicipalitySelected(municipality.code) },
                    )
                }
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Delivery Matrix", style = MaterialTheme.typography.titleLarge)
                    StatusPill("Up to Date", StatusTone.INFO)
                }
            }
            if (useTwoColumns) {
                items(capabilities.chunked(2), key = { row -> row.first().area }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { capability ->
                            CapabilityCard(capability, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(capabilities, key = { "${it.area}-${it.capability}" }) { capability ->
                    CapabilityCard(capability)
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.DataObject, contentDescription = null)
                        Text(
                            "The full roadmap links every stage to tests, performance limits, data formats, and a definition of done.",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionalDataHeader(phase: RegionalDataUiPhase) {
    val status = when (phase) {
        RegionalDataUiPhase.EDITING -> "Configure" to StatusTone.INFO
        RegionalDataUiPhase.REVIEW -> "License Review" to StatusTone.WARNING
        RegionalDataUiPhase.RUNNING -> "Acquiring" to StatusTone.INFO
        RegionalDataUiPhase.COMPLETE -> "Raw Indexed" to StatusTone.POSITIVE
        RegionalDataUiPhase.FAILED -> "Action Required" to StatusTone.NEGATIVE
        RegionalDataUiPhase.CANCELLED -> "Cancelled" to StatusTone.WARNING
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag("regional_data_header"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Regional GIS Packages", style = MaterialTheme.typography.titleLarge)
        StatusPill(status.first, status.second)
    }
}

@Composable
private fun RegionalRequestCard(
    state: RegionalDataUiState,
    onCoordinateChange: (RegionalCoordinateField, String) -> Unit,
    onSelectionToggle: (RegionalDatasetSelection) -> Unit,
    onLiveSnapshotRefreshChange: (Boolean) -> Unit,
    onReview: () -> Unit,
) {
    val editable = state.phase != RegionalDataUiPhase.RUNNING
    Card(
        modifier = Modifier.fillMaxWidth().testTag("regional_request_card"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "WGS84 download bounds",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Select a small region. Raster requests are limited to 1 degree per axis; experimental buildings are limited to 0.05 degrees and 25 km2.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val usePairs = maxWidth >= 280.dp
                if (usePairs) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RegionalCoordinateInput(
                                label = "West",
                                value = state.west,
                                enabled = editable,
                                tag = "regional_west",
                                modifier = Modifier.weight(1f),
                                onValueChange = { onCoordinateChange(RegionalCoordinateField.WEST, it) },
                            )
                            RegionalCoordinateInput(
                                label = "South",
                                value = state.south,
                                enabled = editable,
                                tag = "regional_south",
                                modifier = Modifier.weight(1f),
                                onValueChange = { onCoordinateChange(RegionalCoordinateField.SOUTH, it) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RegionalCoordinateInput(
                                label = "East",
                                value = state.east,
                                enabled = editable,
                                tag = "regional_east",
                                modifier = Modifier.weight(1f),
                                onValueChange = { onCoordinateChange(RegionalCoordinateField.EAST, it) },
                            )
                            RegionalCoordinateInput(
                                label = "North",
                                value = state.north,
                                enabled = editable,
                                tag = "regional_north",
                                modifier = Modifier.weight(1f),
                                onValueChange = { onCoordinateChange(RegionalCoordinateField.NORTH, it) },
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        RegionalCoordinateInput(
                            label = "West",
                            value = state.west,
                            enabled = editable,
                            tag = "regional_west",
                            onValueChange = { onCoordinateChange(RegionalCoordinateField.WEST, it) },
                        )
                        RegionalCoordinateInput(
                            label = "South",
                            value = state.south,
                            enabled = editable,
                            tag = "regional_south",
                            onValueChange = { onCoordinateChange(RegionalCoordinateField.SOUTH, it) },
                        )
                        RegionalCoordinateInput(
                            label = "East",
                            value = state.east,
                            enabled = editable,
                            tag = "regional_east",
                            onValueChange = { onCoordinateChange(RegionalCoordinateField.EAST, it) },
                        )
                        RegionalCoordinateInput(
                            label = "North",
                            value = state.north,
                            enabled = editable,
                            tag = "regional_north",
                            onValueChange = { onCoordinateChange(RegionalCoordinateField.NORTH, it) },
                        )
                    }
                }
            }
            Text("Packages", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                RegionalDatasetSelection.entries.forEach { selection ->
                    FilterChip(
                        selected = selection in state.selections,
                        onClick = { onSelectionToggle(selection) },
                        enabled = editable,
                        label = { Text(selection.shortLabel()) },
                        modifier = Modifier.testTag("regional_selection_${selection.name.lowercase(Locale.US)}"),
                    )
                }
            }
            if (RegionalDatasetSelection.COPERNICUS_GLO_30_DSM in state.selections) {
                RegionalCompactWarning(
                    "Copernicus GLO-30 is a surface model (DSM), not a bare-earth DTM. Supported processed float32 tiles are sampled on demand by the Brazil digital-TV regulatory study.",
                )
            }
            if (RegionalDatasetSelection.ESA_WORLDCOVER_2021 in state.selections) {
                RegionalCompactWarning(
                    "ESA WorldCover classes are source observations, not RF clutter-loss values.",
                )
            }
            if (RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL in state.selections) {
                RegionalCompactWarning(
                    "Buildings use a best-effort bounded Overpass snapshot of OSM building and building-part ways. Height tags are retained but not interpreted; multipolygon relations are omitted.",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.refreshLiveSnapshot,
                        onCheckedChange = onLiveSnapshotRefreshChange,
                        enabled = editable,
                        modifier = Modifier.testTag("regional_refresh_live_snapshot"),
                    )
                    Text(
                        "Refresh the live snapshot now. Otherwise a verified snapshot up to 24 hours old may be reused.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = onReview,
                enabled = state.canReview,
                modifier = Modifier.heightIn(min = 48.dp).testTag("regional_review"),
            ) {
                Text(if (state.plan == null) "Review Download" else "Review Again")
            }
        }
    }
}

@Composable
private fun RegionalCoordinateInput(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.testTag(tag),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
}

@Composable
private fun RegionalCompactWarning(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun RegionalPlanReviewCard(
    state: RegionalDataUiState,
    onLicensesAccepted: (Boolean) -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit,
) {
    val plan = state.plan ?: return
    Card(
        modifier = Modifier.fillMaxWidth().testTag("regional_plan_review"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Download and License Review", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DatasetMetric("Artifacts", plan.artifacts.size.toString())
                DatasetMetric("Estimated", formatStorage(plan.estimatedBytes))
                DatasetMetric("Batch limit", formatStorage(plan.maximumBatchBytes))
                DatasetMetric("Licenses", plan.licenses.size.toString())
            }
            HorizontalDivider()
            plan.artifacts
                .groupingBy { it.source.selection }
                .eachCount()
                .forEach { (selection, count) ->
                    val source = RegionalDatasetCatalog.sourceFor(selection)
                    Text(
                        "$count x ${source.title} | ${source.sourceCrs}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "${source.datasetFamily} / ${source.datasetRelease} | " +
                            "Route ${source.routeId} v${source.routePolicyVersion} | " +
                            "Query ${source.queryVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            HorizontalDivider()
            plan.licenses.forEach { license ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(license.title, style = MaterialTheme.typography.labelLarge)
                    Text(license.attribution, style = MaterialTheme.typography.bodySmall)
                    Text(
                        license.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.licensesAccepted,
                    onCheckedChange = onLicensesAccepted,
                    modifier = Modifier.testTag("regional_license_acceptance"),
                )
                Text(
                    "I reviewed these licenses and will preserve the required attribution.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Downloads use private no-backup storage. Keep ATX Plan open during this in-app operation.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("regional_edit_request"),
                ) {
                    Text("Edit Request")
                }
                Button(
                    onClick = onStart,
                    enabled = state.canAcquire,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("regional_start"),
                ) {
                    Text("Download & Process")
                }
            }
        }
    }
}

@Composable
private fun RegionalProgressCard(
    state: RegionalDataUiState,
    onCancel: () -> Unit,
) {
    val progress = state.progress
    Card(
        modifier = Modifier.fillMaxWidth().testTag("regional_progress_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Acquiring and Processing", style = MaterialTheme.typography.titleMedium)
            Text(
                progress?.artifact?.source?.title ?: "Preparing the first artifact",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                progress?.status?.displayLabel() ?: "Preparing",
                style = MaterialTheme.typography.labelLarge,
            )
            val fraction = progress?.fraction
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testTag("regional_progress"),
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("regional_progress"),
                )
            }
            progress?.let { value ->
                val transfer = if (value.totalBytes == null) {
                    formatStorage(value.completedBytes)
                } else {
                    "${formatStorage(value.completedBytes)} / ${formatStorage(value.totalBytes)}"
                }
                Text(
                    "$transfer | ${formatRate(value.bytesPerSecond)}",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (value.message.isNotBlank()) {
                    Text(value.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "This is not a process-persistent job. Resumable GET partials are preserved after cancellation; the bounded Overpass POST restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("regional_cancel"),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun RegionalResultCard(
    state: RegionalDataUiState,
    onEdit: () -> Unit,
) {
    val result = state.result
    val phasePresentation = when (state.phase) {
        RegionalDataUiPhase.COMPLETE -> Triple("Regional package recorded", StatusTone.POSITIVE, MaterialTheme.colorScheme.secondaryContainer)
        RegionalDataUiPhase.CANCELLED -> Triple("Regional operation cancelled", StatusTone.WARNING, MaterialTheme.colorScheme.surfaceVariant)
        else -> Triple("Regional operation failed", StatusTone.NEGATIVE, MaterialTheme.colorScheme.errorContainer)
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("regional_result_card"),
        colors = CardDefaults.cardColors(containerColor = phasePresentation.third),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(phasePresentation.first, style = MaterialTheme.typography.titleMedium)
                StatusPill(state.phase.displayLabel(), phasePresentation.second)
            }
            state.errorMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (result != null) {
                RegionalResultSummary(result)
            } else {
                Text(
                    "No artifact result was published. No synthetic data was substituted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.heightIn(min = 48.dp).testTag("regional_result_edit"),
            ) {
                Text("Edit / Retry")
            }
        }
    }
}

@Composable
private fun RegionalResultSummary(result: RegionalDownloadResult) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        DatasetMetric("Ready", result.readyCount.toString())
        DatasetMetric("Existing", result.existingCount.toString())
        DatasetMetric(
            "Unavailable",
            result.results.count {
                it.status != RegionalTransferStatus.READY && it.status != RegionalTransferStatus.EXISTING
            }.toString(),
        )
    }
    result.results.take(4).forEach { artifactResult ->
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                artifactResult.artifact.source.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(artifactResult.status.displayLabel())
                    artifactResult.bytes?.let { append(" | ").append(formatStorage(it)) }
                    artifactResult.processedOutput?.let { output ->
                        append(" | ").append(output.format)
                        output.recordCount?.let { append(" | ").append(formatInteger(it)).append(" records") }
                    }
                    artifactResult.effectiveUrl?.let { effectiveUrl ->
                        append(" | ").append(effectiveUrl.endpointHostOrUnknown())
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            artifactResult.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (result.results.size > 4) {
        Text(
            "+${result.results.size - 4} more artifacts recorded in the inventory",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RegionalInventoryCard(state: RegionalDataUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("regional_inventory"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Local Regional Inventory", style = MaterialTheme.typography.titleMedium)
                StatusPill(
                    when (state.inventory.size) {
                        0 -> "Empty"
                        1 -> "1 Artifact"
                        else -> "${state.inventory.size} Artifacts"
                    },
                    if (state.inventory.isEmpty()) StatusTone.INFO else StatusTone.POSITIVE,
                )
            }
            when {
                state.isLoadingInventory -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Reading private-storage inventory records...", style = MaterialTheme.typography.bodySmall)
                }
                state.inventory.isEmpty() -> Text(
                    "No regional artifact has been recorded. Downloads occur only after explicit review and license acceptance.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> RegionalInventorySummary(state.inventory)
            }
        }
    }
}

@Composable
private fun RegionalInventorySummary(inventory: List<RegionalInventoryRecord>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        DatasetMetric("Raw data", formatStorage(inventory.sumOf { it.bytes ?: 0L }))
        DatasetMetric(
            "Indexed / derived",
            inventory.count { it.processingState == RegionalProcessingState.READY }.toString(),
        )
        DatasetMetric(
            "NoData / failed",
            inventory.count {
                it.status == RegionalTransferStatus.NOT_FOUND || it.status == RegionalTransferStatus.FAILED
            }.toString(),
        )
    }
    inventory.take(3).forEach { record ->
        val sourceTitle = RegionalDatasetCatalog.sources
            .firstOrNull { it.datasetId == record.datasetId }
            ?.title
            ?: record.datasetId
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(sourceTitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${record.status.displayLabel()} | ${record.processingState.displayLabel()} | ${record.bytes?.let(::formatStorage) ?: "NoData"}",
                style = MaterialTheme.typography.bodySmall,
            )
            record.processedOutput?.let { output ->
                Text(
                    buildString {
                        append(output.format)
                        output.recordCount?.let { append(" | ").append(formatInteger(it)).append(" records") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "${record.licenseId} | Local SHA-256: ${record.sha256?.take(12) ?: "NoData"} | ${record.checkedAt}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "${record.routeId} v${record.routePolicyVersion} | Served by " +
                    "${record.effectiveUrl.endpointHostOrUnknown()} | Acquired " +
                    (record.acquiredAt ?: "Unknown (migrated record)"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (inventory.size > 3) {
        Text("${inventory.size - 3} more inventory records", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RegionalReadinessNotice() {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("regional_readiness_limitations"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Engineering Readiness", style = MaterialTheme.typography.titleMedium)
            Text(
                "Supported processed Copernicus float32 GeoTIFF tiles are sampled on demand by the Brazil digital-TV regulatory study. Building GeoJSON remains experimental.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "The regulatory study connects Copernicus DSM terrain to HNMT and P.526 paths only. It does not generate a DTM or apply WorldCover, buildings, clutter, or vegetation losses.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun RegionalDatasetSelection.shortLabel(): String = when (this) {
    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM -> "Elevation DSM"
    RegionalDatasetSelection.ESA_WORLDCOVER_2021 -> "Land cover"
    RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL -> "Buildings (experimental)"
}

private fun RegionalTransferStatus.displayLabel(): String = when (this) {
    RegionalTransferStatus.QUEUED -> "Queued"
    RegionalTransferStatus.DOWNLOADING -> "Downloading"
    RegionalTransferStatus.VERIFYING -> "Verifying SHA-256"
    RegionalTransferStatus.PROCESSING -> "Processing"
    RegionalTransferStatus.READY -> "Raw verified"
    RegionalTransferStatus.EXISTING -> "Already verified"
    RegionalTransferStatus.NOT_FOUND -> "NoData / not published"
    RegionalTransferStatus.FAILED -> "Failed"
    RegionalTransferStatus.CANCELLED -> "Cancelled"
}

private fun RegionalProcessingState.displayLabel(): String = when (this) {
    RegionalProcessingState.PENDING -> "Processing pending"
    RegionalProcessingState.PROCESSING -> "Processing"
    RegionalProcessingState.READY -> "Bounded processing complete"
    RegionalProcessingState.FAILED -> "Processing failed"
}

private fun RegionalDataUiPhase.displayLabel(): String = when (this) {
    RegionalDataUiPhase.EDITING -> "Editing"
    RegionalDataUiPhase.REVIEW -> "Review"
    RegionalDataUiPhase.RUNNING -> "Running"
    RegionalDataUiPhase.COMPLETE -> "Complete"
    RegionalDataUiPhase.FAILED -> "Failed"
    RegionalDataUiPhase.CANCELLED -> "Cancelled"
}

private fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MiB/s", bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024.0 -> String.format(Locale.US, "%.1f KiB/s", bytesPerSecond / 1024.0)
    else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
}

@Composable
private fun IbgeDatasetHeader(status: IbgeCatalogStatus) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Embedded IBGE Dataset", style = MaterialTheme.typography.titleLarge)
        val (label, tone) = when (status) {
            IbgeCatalogStatus.CHECKING -> "Checking" to StatusTone.INFO
            IbgeCatalogStatus.INSTALLING -> "Installing" to StatusTone.INFO
            IbgeCatalogStatus.VALIDATING -> "Validating" to StatusTone.INFO
            IbgeCatalogStatus.READY -> "Ready Offline" to StatusTone.POSITIVE
            IbgeCatalogStatus.FAILED -> "Unavailable" to StatusTone.NEGATIVE
        }
        StatusPill(label, tone)
    }
}

@Composable
private fun IbgeDatasetStatusCard(
    state: DataCatalogUiState,
    onRetry: () -> Unit,
) {
    when (state.ibgeStatus) {
        IbgeCatalogStatus.CHECKING,
        IbgeCatalogStatus.INSTALLING,
        IbgeCatalogStatus.VALIDATING,
        -> IbgePreparingCard(state)

        IbgeCatalogStatus.READY -> {
            val descriptor = state.ibgeDescriptor
            if (descriptor == null) {
                IbgeFailureCard(
                    message = "The dataset reached an inconsistent ready state. Retry verification.",
                    onRetry = onRetry,
                )
            } else {
                IbgeReadyCard(descriptor)
            }
        }

        IbgeCatalogStatus.FAILED -> IbgeFailureCard(
            message = state.datasetErrorMessage ?: "The offline IBGE dataset is unavailable.",
            onRetry = onRetry,
        )
    }
}

@Composable
private fun IbgePreparingCard(state: DataCatalogUiState) {
    val progress = state.ibgeProgress
    val progressFraction = progress?.fraction
    val title = when (state.ibgeStatus) {
        IbgeCatalogStatus.CHECKING -> "Checking the embedded package"
        IbgeCatalogStatus.INSTALLING -> "Installing the verified database"
        IbgeCatalogStatus.VALIDATING -> "Validating SQLite tables and metadata"
        else -> "Preparing the offline dataset"
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ibge_dataset_preparing"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Storage, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The operation runs in private storage and does not use the network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("ibge_install_progress"),
                )
                Text(
                    "${formatStorage(progress.completedBytes)} / ${formatStorage(progress.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun IbgeFailureCard(
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ibge_dataset_failure"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("IBGE dataset unavailable", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "No municipality or population result is substituted while the dataset is unavailable.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp).testTag("retry_ibge_dataset"),
            ) {
                Text("Retry Verification")
            }
        }
    }
}

@Composable
private fun IbgeReadyCard(descriptor: IbgeDatasetDescriptor) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ibge_dataset_ready"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(descriptor.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Bundled, SHA-256 verified, and opened read-only from private no-backup storage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                DatasetMetric("Sectors", formatInteger(descriptor.sectorCount.toLong()))
                DatasetMetric("Municipalities", formatInteger(descriptor.municipalityCount.toLong()))
                DatasetMetric("Population", formatInteger(descriptor.populationTotal))
                DatasetMetric("Installed", formatStorage(descriptor.installedByteCount))
                DatasetMetric("Packaged", formatStorage(descriptor.compressedByteCount))
                DatasetMetric("Source CRS", descriptor.sourceCrs)
            }
            HorizontalDivider()
            Text(descriptor.attribution, style = MaterialTheme.typography.labelMedium)
            Text(
                "Source records without a valid municipality code: " +
                    "${formatInteger(descriptor.unassignedSectorCount.toLong())}. They remain explicitly unassigned. " +
                    "Missing population records: ${formatInteger(descriptor.missingPopulationSectorCount.toLong())}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Attributes and portable bounding-box records are included; census-sector polygons are not. " +
                    "This package cannot render official sector boundaries, resolve exact point containment, " +
                    "or calculate population inside a coverage contour.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                descriptor.licenseStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Database SHA-256: ${descriptor.databaseSha256.take(16)}… | Source accessed ${descriptor.sourceAccessedOn}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DatasetMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InlineDatasetMessage(message: String, error: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Search,
                contentDescription = null,
            )
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MunicipalityResultCard(
    municipality: IbgeMunicipalitySummary,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { this.selected = selected }
            .testTag("ibge_municipality_${municipality.code}"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(15.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.LocationCity, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${municipality.name}, ${municipality.stateAbbreviation}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "IBGE ${municipality.code} | ${formatInteger(municipality.populationTotal)} people | " +
                            "${formatInteger(municipality.sectorCount.toLong())} sectors",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (selected) {
                HorizontalDivider()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DatasetMetric("State", municipality.stateName)
                    DatasetMetric("Urban population", formatInteger(municipality.urbanPopulation))
                    DatasetMetric(
                        "Urban share",
                        municipality.urbanPopulationFraction
                            ?.let { fraction -> String.format(Locale.US, "%.1f%%", fraction * 100.0) }
                            ?: "NoData",
                    )
                    DatasetMetric("Area", "${formatDecimal(municipality.areaTotalKm2)} km²")
                    DatasetMetric(
                        "Population NoData",
                        formatInteger(municipality.missingPopulationSectorCount.toLong()),
                    )
                }
                Text(
                    "SIRGAS 2000 envelope: " +
                        "${formatCoordinate(municipality.west)}, ${formatCoordinate(municipality.south)} to " +
                        "${formatCoordinate(municipality.east)}, ${formatCoordinate(municipality.north)}. " +
                        "This envelope is not an official municipal boundary.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PrincipleCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatInteger(value: Long): String = String.format(Locale.US, "%,d", value)

private fun formatDecimal(value: Double): String = String.format(Locale.US, "%,.1f", value)

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f°", value)

private fun formatStorage(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun String?.endpointHostOrUnknown(): String = try {
    this?.let { java.net.URI(it).host }?.takeIf(String::isNotBlank) ?: "Unknown endpoint"
} catch (_: Exception) {
    "Unknown endpoint"
}

@Composable
private fun CapabilityCard(capability: Capability, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(capability.area, style = MaterialTheme.typography.labelLarge)
                StatusPill(capability.stage, capability.tone)
            }
            Text(capability.capability, style = MaterialTheme.typography.titleMedium)
            Text(
                capability.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
