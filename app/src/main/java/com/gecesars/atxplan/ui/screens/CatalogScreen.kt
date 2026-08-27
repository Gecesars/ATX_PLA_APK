package com.gecesars.atxplan.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.dataset.DataCatalogUiState
import com.gecesars.atxplan.ui.dataset.IbgeCatalogStatus
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
        "Regional DEM, clutter, and buildings",
        "Planned",
        StatusTone.WARNING,
        "Bounding-box downloads with hashes, license metadata, resume support, and a disk budget.",
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
        "Anatel and IBGE population data",
        "Bounded index delivered",
        StatusTone.INFO,
        "The verified national IBGE 2022 attribute index and portable sector envelopes are bundled. Sector polygons, population-by-coverage, and regulatory conclusions remain planned.",
    ),
)

@Composable
fun CatalogScreen(
    state: DataCatalogUiState = DataCatalogUiState(),
    onMunicipalityQueryChange: (String) -> Unit = {},
    onMunicipalitySelected: (String) -> Unit = {},
    onRetryDataset: () -> Unit = {},
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
