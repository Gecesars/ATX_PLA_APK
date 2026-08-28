package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.OfficialAnatelBasicPlanSource
import com.gecesars.atxplan.ui.anatel.AnatelBasicPlanUiPhase
import com.gecesars.atxplan.ui.anatel.AnatelBasicPlanUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AnatelBasicPlanCatalogSection(
    state: AnatelBasicPlanUiState,
    onLicenseReviewAcknowledged: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onServiceSelected: (AnatelBroadcastService) -> Unit,
    onQueryTextChange: (String) -> Unit,
    onStateCodeChange: (String) -> Unit,
    onChannelChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadPrevious: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val source = OfficialAnatelBasicPlanSource.descriptor
    var sourceTermsOpenFailed by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("anatel_basic_plan_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Storage, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Anatel Basic Plan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Anatel TV/FM source records · immutable raw snapshot · offline index",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${source.license.attribution} The app downloads Canais.zip only after this " +
                        "explicit action, computes SHA-256 for local integrity and content identity, " +
                        "preserves the raw ZIP, and publishes a staged SQLite index atomically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "The source terms remain marked Review required. Catalog refresh never changes " +
                        "a project, station, RF parameter, or regulatory status automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        sourceTermsOpenFailed = runCatching {
                            uriHandler.openUri(source.license.termsUrl)
                        }.isFailure
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("anatel_open_source_terms"),
                ) {
                    Text("Open Official Source Terms")
                }
                if (sourceTermsOpenFailed) {
                    Text(
                        "No application is available to open the official source terms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.licenseReviewAcknowledged,
                        onCheckedChange = onLicenseReviewAcknowledged,
                        enabled = state.phase != AnatelBasicPlanUiPhase.REFRESHING,
                        modifier = Modifier.testTag("anatel_source_review"),
                    )
                    Text(
                        "I reviewed the official source and attribution.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = onRefresh,
                    enabled = state.licenseReviewAcknowledged &&
                        state.phase != AnatelBasicPlanUiPhase.REFRESHING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("anatel_refresh"),
                ) {
                    if (state.phase == AnatelBasicPlanUiPhase.REFRESHING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            if (state.snapshot == null) {
                                Icons.Outlined.CloudDownload
                            } else {
                                Icons.Outlined.Refresh
                            },
                            contentDescription = null,
                        )
                    }
                    Text(
                        if (state.snapshot == null) "Download & Index" else "Refresh Snapshot",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        state.errorMessage?.let { message ->
            CatalogMessageCard(message, isError = true, onDismiss = onDismissMessage)
        }
        state.notice?.let { message ->
            CatalogMessageCard(message, isError = false, onDismiss = onDismissMessage)
        }

        when (state.phase) {
            AnatelBasicPlanUiPhase.CHECKING -> AnatelProgressCard(
                "Checking the local Basic Plan snapshot",
            )

            AnatelBasicPlanUiPhase.REFRESHING -> AnatelProgressCard(
                "Downloading, integrity-checking, and indexing the Anatel source snapshot. " +
                    "Keep the app open.",
            )

            AnatelBasicPlanUiPhase.NOT_ACQUIRED -> AnatelNoDataCard(
                "No locally indexed Basic Plan snapshot is available " +
                    "(${state.noDataReason.displayLabel()}).",
            )

            AnatelBasicPlanUiPhase.FAILED -> AnatelNoDataCard(
                "The catalog is unavailable. No channel record was substituted.",
            )

            AnatelBasicPlanUiPhase.READY -> {
                state.snapshot?.let { snapshot -> AnatelSnapshotCard(snapshot) }
                AnatelQueryCard(
                    state = state,
                    onServiceSelected = onServiceSelected,
                    onQueryTextChange = onQueryTextChange,
                    onStateCodeChange = onStateCodeChange,
                    onChannelChange = onChannelChange,
                    onSearch = onSearch,
                )
                AnatelResultsCard(state, onLoadPrevious, onLoadMore)
            }
        }
    }
}

@Composable
private fun AnatelProgressCard(message: String) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnatelNoDataCard(message: String) {
    Card(modifier = Modifier.testTag("anatel_no_data")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("NoData", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnatelSnapshotCard(snapshot: AnatelBasicPlanCatalogSnapshot) {
    val report = snapshot.report
    Card(modifier = Modifier.testTag("anatel_snapshot_ready")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("Locally Indexed Snapshot", style = MaterialTheme.typography.titleSmall)
            Text(
                "${report.emittedRecordCount} indexed source records · latest entry generation date " +
                    report.latestGenerationDate.displayValue(maximumCharacters = 32),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "SHA-256 ${report.verifiedArchiveSha256.take(16)}… · " +
                    "${formatBytes(report.verifiedArchiveByteCount)} · indexed " +
                    formatTimestamp(snapshot.indexedAtEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.warnings.isNotEmpty()) {
                Text(
                    "${report.warnings.sumOf { warning -> warning.occurrenceCount }} source warning(s); " +
                        "open record details before engineering use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AnatelQueryCard(
    state: AnatelBasicPlanUiState,
    onServiceSelected: (AnatelBroadcastService) -> Unit,
    onQueryTextChange: (String) -> Unit,
    onStateCodeChange: (String) -> Unit,
    onChannelChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Search Offline Catalog", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    AnatelBroadcastService.FM to "FM",
                    AnatelBroadcastService.TELEVISION to "Digital TV / TV",
                ).forEach { (service, label) ->
                    FilterChip(
                        selected = state.service == service,
                        onClick = { onServiceSelected(service) },
                        label = { Text(label, maxLines = 1) },
                        enabled = !state.isSearching,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.stateCode,
                    onValueChange = onStateCodeChange,
                    label = { Text("State") },
                    placeholder = { Text("SP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("anatel_state_filter"),
                )
                OutlinedTextField(
                    value = state.channelText,
                    onValueChange = onChannelChange,
                    label = { Text("Channel") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("anatel_channel_filter"),
                )
            }
            OutlinedTextField(
                value = state.queryText,
                onValueChange = onQueryTextChange,
                label = { Text("Entity, municipality, ID, or source text") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("anatel_text_filter"),
            )
            Button(
                onClick = onSearch,
                enabled = !state.isSearching,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("anatel_search"),
            ) {
                if (state.isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                }
                Text("Search", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun AnatelResultsCard(
    state: AnatelBasicPlanUiState,
    onLoadPrevious: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Card(modifier = Modifier.testTag("anatel_results")) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.isSearching) {
                    "Searching this offline snapshot…"
                } else if (state.filtersDirty) {
                    "Edited filters are not applied. Tap Search."
                } else if (state.records.isEmpty()) {
                    "No records match the current filters."
                } else {
                    val first = state.resultOffset + 1
                    val last = state.resultOffset + state.records.size
                    "Records $first-$last · ${state.records.size} on this page"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(12.dp),
            )
            state.records.forEachIndexed { index, record ->
                if (index > 0) HorizontalDivider()
                AnatelRecordRow(record)
            }
            if (!state.filtersDirty && (state.resultOffset > 0 || state.hasMore)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onLoadPrevious,
                        enabled = !state.isSearching && state.resultOffset > 0,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("anatel_previous_page"),
                    ) {
                        Text("Previous")
                    }
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.isSearching && state.hasMore,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("anatel_load_more"),
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun AnatelRecordRow(record: AnatelBasicPlanRecord) {
    var detailsExpanded by rememberSaveable(
        record.sourceRowId,
        record.provenance.entryName,
        record.provenance.sourceRowNumber,
    ) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            record.entityName.displayValue("Unnamed entity", 160),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(record.service.displayLabel())
                append(" · channel ")
                append(
                    record.channel?.toString()
                        ?: record.channelRaw.displayValue(maximumCharacters = 16),
                )
                append(" · ")
                append(record.frequency.frequencyMHz?.let { value ->
                    String.format(Locale.US, "%.3f MHz", value)
                } ?: "frequency NoData")
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${record.municipalityName.displayValue("Municipality NoData", 80)} / " +
                "${record.stateCode.displayValue("state NoData", 8)} · class " +
                "${record.stationClassRaw.displayValue(maximumCharacters = 32)} · raw status " +
                "${record.status.normalizedCode.displayValue(maximumCharacters = 32)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "ERP ${record.erpKw?.let { value -> String.format(Locale.US, "%.3f kW", value) } ?: "NoData"} · " +
                "antenna height ${record.antennaHeightMeters?.let { value ->
                    String.format(Locale.US, "%.1f m", value)
                } ?: "NoData"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.latitudeDegrees != null && record.longitudeDegrees != null) {
            Text(
                String.format(
                    Locale.US,
                    "Coordinates %.6f, %.6f",
                    record.latitudeDegrees,
                    record.longitudeDegrees,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${record.origin.displayLabel()} · ${record.frequency.origin.displayLabel()} · " +
                "${record.basicPlanId?.let { id -> "PB ${id.take(64)}" } ?: "PB ID NoData"} · " +
                "source row ${record.provenance.sourceRowNumber}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = { detailsExpanded = !detailsExpanded },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (detailsExpanded) "Hide Source Details" else "Show Source Details")
        }
        if (detailsExpanded) {
            SourceRecordDetails(record)
        }
    }
}

@Composable
private fun SourceRecordDetails(record: AnatelBasicPlanRecord) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SourceDetail("Raw service / status", "${record.rawService} / ${record.status.rawCode}")
        SourceDetail(
            "Raw channel / frequency / offset",
            "${record.channelRaw} / ${record.frequency.sourceFrequencyRaw} / ${record.channelOffsetRaw}",
        )
        SourceDetail(
            "Purpose / character / station category",
            "${record.purposeRaw} / ${record.characterRaw} / ${record.stationCategoryRaw}",
        )
        SourceDetail("Antenna limitations", record.antennaLimitationsRaw)
        SourceDetail("Antenna pattern dBd", record.antennaPatternDbdRaw)
        SourceDetail("Observations", record.observationsRaw)
        SourceDetail(
            "Source IDs",
            "row ${record.sourceRowId.orEmpty()} · Fistel ${record.fistelRaw} · " +
                "generator ${record.generatorFistelRaw} · DIC ${record.dicRaw}",
        )
    }
}

@Composable
private fun SourceDetail(label: String, rawValue: String) {
    Text(
        "$label: ${rawValue.displayValue(maximumCharacters = 512)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CatalogMessageCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Dismiss")
            }
        }
    }
}

private fun AnatelBroadcastService.displayLabel(): String = when (this) {
    AnatelBroadcastService.FM -> "FM"
    AnatelBroadcastService.TELEVISION -> "TV"
    AnatelBroadcastService.UNKNOWN -> "Unknown service"
}

private fun AnatelFrequencyOrigin.displayLabel(): String = when (this) {
    AnatelFrequencyOrigin.SOURCE_ATTRIBUTE -> "source frequency"
    AnatelFrequencyOrigin.CHANNEL_FALLBACK -> "channel fallback"
    AnatelFrequencyOrigin.NO_DATA -> "frequency NoData"
}

private fun AnatelBasicPlanOrigin.displayLabel(): String = when (this) {
    AnatelBasicPlanOrigin.BASIC_PLAN -> "Basic Plan"
    AnatelBasicPlanOrigin.SECONDARY_CHANNELS -> "Secondary channels"
    AnatelBasicPlanOrigin.REQUESTS -> "Requests"
}

private fun com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason?.displayLabel(): String =
    when (this) {
        com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason.NOT_ACQUIRED,
        null,
        -> "not acquired"

        com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason.CURRENT_POINTER_INVALID ->
            "current pointer invalid"

        com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason.RAW_ARCHIVE_UNAVAILABLE ->
            "raw archive unavailable"

        com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason.INDEX_UNAVAILABLE ->
            "index unavailable"

        com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason.INDEX_INCOMPATIBLE ->
            "index incompatible"
    }

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US)
        .format(Date(epochMillis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> String.format(Locale.US, "%.1f KiB", bytes / 1_024.0)
    else -> String.format(Locale.US, "%.1f MiB", bytes / 1_048_576.0)
}

private fun String?.displayValue(
    fallback: String = "NoData",
    maximumCharacters: Int,
): String = this?.trim()?.takeIf(String::isNotEmpty)?.take(maximumCharacters) ?: fallback
