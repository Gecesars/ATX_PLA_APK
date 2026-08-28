package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gecesars.atxplan.domain.application.RfAssetKind
import com.gecesars.atxplan.domain.application.RfAssetMutationCommand
import com.gecesars.atxplan.domain.application.RfAssetMutationReceipt
import com.gecesars.atxplan.domain.application.RfAssetMutationStatus
import com.gecesars.atxplan.domain.application.RfNetworkInput
import com.gecesars.atxplan.domain.application.RfReceiverInput
import com.gecesars.atxplan.domain.application.RfSectorInput
import com.gecesars.atxplan.domain.application.RfSiteInput
import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.BandwidthMHz
import com.gecesars.atxplan.domain.model.FrequencyMHz
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.LatitudeDegrees
import com.gecesars.atxplan.domain.model.LongitudeDegrees
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.model.TiltDegrees
import com.gecesars.atxplan.ui.components.ScreenHeader
import java.util.Locale
import java.util.UUID

private enum class RfAssetTab(val label: String, val kind: RfAssetKind) {
    NETWORKS("Networks", RfAssetKind.NETWORK),
    SITES("Sites", RfAssetKind.SITE),
    SECTORS("Sectors", RfAssetKind.SECTOR),
    RECEIVERS("Receivers", RfAssetKind.RECEIVER),
}

@Composable
fun RfAssetsScreen(
    project: PlannerProject?,
    isLoadingCatalog: Boolean,
    isCatalogWritable: Boolean,
    isSaving: Boolean,
    catalogMutationCompletionCount: Long = 0L,
    activeMutationRequestId: String? = null,
    lastMutationReceipt: RfAssetMutationReceipt?,
    onMutate: (RfAssetMutationCommand) -> Unit,
    onOpenAntennaPatterns: () -> Unit = {},
    onBack: () -> Unit,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var editorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editorEntityId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorSiteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteKind by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteEntityId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSiteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCompletionCount by rememberSaveable { mutableStateOf<Long?>(null) }

    fun closeEditor() {
        editorKind = null
        editorEntityId = null
        editorSiteId = null
    }

    fun closeDelete() {
        deleteKind = null
        deleteEntityId = null
        deleteSiteId = null
    }

    LaunchedEffect(
        pendingRequestId,
        activeMutationRequestId,
        lastMutationReceipt?.requestId,
        catalogMutationCompletionCount,
        isSaving,
    ) {
        val requestId = pendingRequestId ?: activeMutationRequestId ?: return@LaunchedEffect
        if (pendingRequestId == null) {
            pendingRequestId = requestId
            pendingCompletionCount = catalogMutationCompletionCount
        }
        val receipt = lastMutationReceipt?.takeIf { it.requestId == requestId }
        if (receipt == null) {
            val baseline = pendingCompletionCount ?: return@LaunchedEffect
            if (
                activeMutationRequestId != requestId &&
                !isSaving &&
                catalogMutationCompletionCount != baseline
            ) {
                // Validation and physical storage failures do not produce a durable RF receipt.
                // Release the transient pending state so the reviewed dialog can be retried.
                pendingRequestId = null
                pendingCompletionCount = null
            }
            return@LaunchedEffect
        }
        pendingRequestId = null
        pendingCompletionCount = null
        when (receipt.status) {
            RfAssetMutationStatus.CREATED,
            RfAssetMutationStatus.UPDATED,
            RfAssetMutationStatus.DELETED,
            RfAssetMutationStatus.UNCHANGED,
            -> {
                closeEditor()
                closeDelete()
            }
            RfAssetMutationStatus.NOT_FOUND -> {
                closeEditor()
                closeDelete()
            }
            RfAssetMutationStatus.STALE,
            RfAssetMutationStatus.BLOCKED_REFERENCES,
            -> Unit
        }
    }

    if (project == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(
                title = if (isLoadingCatalog) "Loading RF Assets" else "Project Unavailable",
                subtitle = if (isLoadingCatalog) {
                    "The durable project document is being opened."
                } else {
                    "This project is no longer active in the local catalog."
                },
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                Text("Back to Projects")
            }
        }
        return
    }

    val selectedTab = RfAssetTab.entries[selectedTabIndex]
    val mutationPending = pendingRequestId != null || activeMutationRequestId != null
    val canMutate = isCatalogWritable && !isSaving && !mutationPending
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("rf_assets_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "RF Assets",
                subtitle = "Edit networks, sites, sectors, and receivers for " +
                    "${boundedProjectNameForDisplay(project.name)}. " +
                    "Changes are committed to the latest durable project document.",
            )
        }
        item {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RfAssetTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(tab.label, maxLines = 1) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        item {
            RfAssetSummary(project)
        }
        item {
            OutlinedButton(
                onClick = onOpenAntennaPatterns,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.SettingsInputAntenna, contentDescription = null)
                Text("Antenna Pattern Lab")
            }
        }
        item {
            Button(
                onClick = {
                    editorKind = selectedTab.kind.name
                    editorEntityId = null
                    editorSiteId = null
                },
                enabled = canMutate && when (selectedTab) {
                    RfAssetTab.SECTORS -> project.sites.isNotEmpty()
                    RfAssetTab.RECEIVERS -> project.networks.isNotEmpty()
                    else -> true
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_rf_asset_button"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("Add ${selectedTab.label.dropLast(1)}")
            }
        }
        when {
            selectedTab == RfAssetTab.SECTORS && project.sites.isEmpty() -> item {
                RfAssetPrerequisiteMessage("Add a transmitter site before adding a sector.")
            }
            selectedTab == RfAssetTab.RECEIVERS && project.networks.isEmpty() -> item {
                RfAssetPrerequisiteMessage("Add a network before adding a receiver.")
            }
        }
        when (selectedTab) {
            RfAssetTab.NETWORKS -> {
                if (project.networks.isEmpty()) item { EmptyRfAssetMessage("No networks are stored.") }
                items(project.networks, key = RfNetwork::id) { network ->
                    val sectorCount = project.sites.sumOf { site ->
                        site.sectors.count { it.networkId == network.id }
                    }
                    val receiverCount = project.receivers.count { receiver ->
                        receiver.networkId == network.id ||
                            receiver.networkProfiles.any { profile -> profile.networkId == network.id }
                    }
                    RfAssetCard(
                        title = network.name,
                        subtitle = "${radioSystemDisplayName(network.system)} · ${formatNumber(network.downlinkFrequencyMHz)} MHz · " +
                            "${formatNumber(network.bandwidthMHz)} MHz BW",
                        metadata = "${rfCountLabel(sectorCount, "sector", "sectors")} · " +
                            rfCountLabel(receiverCount, "receiver", "receivers"),
                        enabled = canMutate,
                        onEdit = {
                            editorKind = RfAssetKind.NETWORK.name
                            editorEntityId = network.id
                        },
                        onDelete = {
                            deleteKind = RfAssetKind.NETWORK.name
                            deleteEntityId = network.id
                        },
                    )
                }
            }
            RfAssetTab.SITES -> {
                if (project.sites.isEmpty()) item { EmptyRfAssetMessage("No transmitter sites are stored.") }
                items(project.sites, key = RadioSite::id) { site ->
                    RfAssetCard(
                        title = site.name,
                        subtitle = "${formatCoordinate(site.location.latitude)}, " +
                            formatCoordinate(site.location.longitude),
                        metadata = rfCountLabel(
                            site.sectors.size,
                            "contained sector",
                            "contained sectors",
                        ),
                        enabled = canMutate,
                        onEdit = {
                            editorKind = RfAssetKind.SITE.name
                            editorEntityId = site.id
                        },
                        onDelete = {
                            deleteKind = RfAssetKind.SITE.name
                            deleteEntityId = site.id
                        },
                    )
                }
            }
            RfAssetTab.SECTORS -> {
                val sectors = project.sites.flatMap { site -> site.sectors.map { site to it } }
                if (sectors.isEmpty()) item { EmptyRfAssetMessage("No sectors are stored.") }
                items(sectors, key = { (site, sector) -> "${site.id}/${sector.id}" }) { (site, sector) ->
                    val network = project.networks.firstOrNull { it.id == sector.networkId }
                    RfAssetCard(
                        title = sector.name,
                        subtitle = "${site.name} · ${network?.name ?: "Unassigned"}",
                        metadata = "${formatNumber(sector.frequencyMHz)} MHz · " +
                            "${formatNumber(sector.azimuthDegrees)}° · ${if (sector.active) "Active" else "Inactive"}",
                        enabled = canMutate,
                        onEdit = {
                            editorKind = RfAssetKind.SECTOR.name
                            editorEntityId = sector.id
                            editorSiteId = site.id
                        },
                        onDelete = {
                            deleteKind = RfAssetKind.SECTOR.name
                            deleteEntityId = sector.id
                            deleteSiteId = site.id
                        },
                    )
                }
            }
            RfAssetTab.RECEIVERS -> {
                if (project.receivers.isEmpty()) item { EmptyRfAssetMessage("No receivers are stored.") }
                items(project.receivers, key = Receiver::id) { receiver ->
                    val network = project.networks.firstOrNull { it.id == receiver.networkId }
                    RfAssetCard(
                        title = receiver.name,
                        subtitle = network?.name ?: "Missing network reference",
                        metadata = "${formatCoordinate(receiver.location.latitude.value)}, " +
                            "${formatCoordinate(receiver.location.longitude.value)} · " +
                            "${formatNumber(receiver.sensitivityDbm.value)} dBm sensitivity",
                        compatibilityNote = receiverCompatibilitySummary(receiver, project.networks),
                        enabled = canMutate,
                        onEdit = {
                            editorKind = RfAssetKind.RECEIVER.name
                            editorEntityId = receiver.id
                        },
                        onDelete = {
                            deleteKind = RfAssetKind.RECEIVER.name
                            deleteEntityId = receiver.id
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.heightIn(min = 8.dp)) }
    }

    editorKind?.let { rawKind ->
        val kind = runCatching { RfAssetKind.valueOf(rawKind) }.getOrNull() ?: return@let
        when (kind) {
            RfAssetKind.NETWORK -> {
                val isCreate = editorEntityId == null
                val existing = editorEntityId?.let { id -> project.networks.firstOrNull { it.id == id } }
                if (!isCreate && existing == null) {
                    RfEditorUnavailableDialog(
                        assetLabel = "Network",
                        message = "This network no longer exists in the latest project document. " +
                            "Close the editor and review the RF asset list.",
                        onDismiss = ::closeEditor,
                    )
                } else {
                    NetworkEditorDialog(
                        existing = existing,
                        isSaving = isSaving || mutationPending,
                        onDismiss = ::closeEditor,
                        onSubmit = { input ->
                            val requestId = newRfRequestId()
                            pendingRequestId = requestId
                            pendingCompletionCount = catalogMutationCompletionCount
                            onMutate(
                                if (isCreate) {
                                    RfAssetMutationCommand.CreateNetwork(project.id, input, requestId)
                                } else {
                                    RfAssetMutationCommand.UpdateNetwork(
                                        project.id,
                                        checkNotNull(existing),
                                        input,
                                        requestId,
                                    )
                                },
                            )
                        },
                    )
                }
            }
            RfAssetKind.SITE -> {
                val isCreate = editorEntityId == null
                val existing = editorEntityId?.let { id -> project.sites.firstOrNull { it.id == id } }
                if (!isCreate && existing == null) {
                    RfEditorUnavailableDialog(
                        assetLabel = "Site",
                        message = "This site no longer exists in the latest project document. " +
                            "Close the editor and review the RF asset list.",
                        onDismiss = ::closeEditor,
                    )
                } else {
                    SiteEditorDialog(
                        existing = existing,
                        isSaving = isSaving || mutationPending,
                        onDismiss = ::closeEditor,
                        onSubmit = { input ->
                            val requestId = newRfRequestId()
                            pendingRequestId = requestId
                            pendingCompletionCount = catalogMutationCompletionCount
                            onMutate(
                                if (isCreate) {
                                    RfAssetMutationCommand.CreateSite(project.id, input, requestId)
                                } else {
                                    RfAssetMutationCommand.UpdateSite(
                                        project.id,
                                        checkNotNull(existing),
                                        input,
                                        requestId,
                                    )
                                },
                            )
                        },
                    )
                }
            }
            RfAssetKind.SECTOR -> {
                val isCreate = editorEntityId == null
                val existingSite = editorSiteId?.let { id -> project.sites.firstOrNull { it.id == id } }
                val existing = existingSite?.sectors?.firstOrNull { it.id == editorEntityId }
                when {
                    !isCreate && (existingSite == null || existing == null) -> {
                        RfEditorUnavailableDialog(
                            assetLabel = "Sector",
                            message = "This sector or its transmitter site no longer exists in the latest " +
                                "project document. Close the editor and review the RF asset list.",
                            onDismiss = ::closeEditor,
                        )
                    }
                    isCreate && project.sites.isEmpty() -> {
                        RfEditorUnavailableDialog(
                            assetLabel = "Sector Editor",
                            message = "Add a transmitter site before adding a sector.",
                            onDismiss = ::closeEditor,
                        )
                    }
                    else -> {
                        SectorEditorDialog(
                            existing = existing,
                            existingSite = existingSite,
                            sites = project.sites,
                            networks = project.networks,
                            isSaving = isSaving || mutationPending,
                            onDismiss = ::closeEditor,
                            onSubmit = { siteId, input ->
                                val requestId = newRfRequestId()
                                pendingRequestId = requestId
                                pendingCompletionCount = catalogMutationCompletionCount
                                onMutate(
                                    if (isCreate) {
                                        RfAssetMutationCommand.CreateSector(project.id, siteId, input, requestId)
                                    } else {
                                        RfAssetMutationCommand.UpdateSector(
                                            project.id,
                                            checkNotNull(existingSite).id,
                                            checkNotNull(existing),
                                            input,
                                            requestId,
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
            }
            RfAssetKind.RECEIVER -> {
                val isCreate = editorEntityId == null
                val existing = editorEntityId?.let { id -> project.receivers.firstOrNull { it.id == id } }
                when {
                    !isCreate && existing == null -> {
                        RfEditorUnavailableDialog(
                            assetLabel = "Receiver",
                            message = "This receiver no longer exists in the latest project document. " +
                                "Close the editor and review the RF asset list.",
                            onDismiss = ::closeEditor,
                        )
                    }
                    project.networks.isEmpty() -> {
                        RfEditorUnavailableDialog(
                            assetLabel = "Receiver Editor",
                            message = "Add a network before adding or editing a receiver.",
                            onDismiss = ::closeEditor,
                        )
                    }
                    existing != null && project.networks.none { it.id == existing.networkId } -> {
                        RfEditorUnavailableDialog(
                            assetLabel = "Receiver Editor",
                            message = "The receiver's primary network is unavailable. Restore that network " +
                                "before editing this receiver.",
                            onDismiss = ::closeEditor,
                        )
                    }
                    else -> {
                        ReceiverEditorDialog(
                            existing = existing,
                            networks = project.networks,
                            isSaving = isSaving || mutationPending,
                            onDismiss = ::closeEditor,
                            onSubmit = { input ->
                                val requestId = newRfRequestId()
                                pendingRequestId = requestId
                                pendingCompletionCount = catalogMutationCompletionCount
                                onMutate(
                                    if (isCreate) {
                                        RfAssetMutationCommand.CreateReceiver(project.id, input, requestId)
                                    } else {
                                        RfAssetMutationCommand.UpdateReceiver(
                                            project.id,
                                            checkNotNull(existing),
                                            input,
                                            requestId,
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    deleteKind?.let { rawKind ->
        val kind = runCatching { RfAssetKind.valueOf(rawKind) }.getOrNull() ?: return@let
        val network = if (kind == RfAssetKind.NETWORK) {
            project.networks.firstOrNull { it.id == deleteEntityId }
        } else null
        val site = if (kind == RfAssetKind.SITE) {
            project.sites.firstOrNull { it.id == deleteEntityId }
        } else null
        val sectorSite = if (kind == RfAssetKind.SECTOR) {
            project.sites.firstOrNull { it.id == deleteSiteId }
        } else null
        val sector = sectorSite?.sectors?.firstOrNull { it.id == deleteEntityId }
        val receiver = if (kind == RfAssetKind.RECEIVER) {
            project.receivers.firstOrNull { it.id == deleteEntityId }
        } else null
        val entityName = network?.name ?: site?.name ?: sector?.name ?: receiver?.name
        if (entityName != null) {
            val preservedProfileReferenceCount = if (kind == RfAssetKind.NETWORK) {
                project.receivers.count { currentReceiver ->
                    currentReceiver.networkProfiles.any { profile -> profile.networkId == network?.id }
                }
            } else {
                0
            }
            val impact = when (kind) {
                RfAssetKind.NETWORK -> Pair(
                    project.sites.sumOf { currentSite ->
                        currentSite.sectors.count { it.networkId == network?.id }
                    },
                    project.receivers.count { receiver ->
                        receiver.networkId == network?.id ||
                            receiver.networkProfiles.any { profile -> profile.networkId == network?.id }
                    },
                )
                RfAssetKind.SITE -> Pair(site?.sectors?.size ?: 0, 0)
                else -> Pair(0, 0)
            }
            val blocked = kind == RfAssetKind.NETWORK && (impact.first > 0 || impact.second > 0)
            DeleteRfAssetDialog(
                kind = kind,
                entityName = entityName,
                sectorCount = impact.first,
                receiverCount = impact.second,
                preservedProfileReferenceCount = preservedProfileReferenceCount,
                blocked = blocked,
                isSaving = isSaving || mutationPending,
                onDismiss = ::closeDelete,
                onConfirm = {
                    val requestId = newRfRequestId()
                    pendingRequestId = requestId
                    pendingCompletionCount = catalogMutationCompletionCount
                    val command = when (kind) {
                        RfAssetKind.NETWORK -> RfAssetMutationCommand.DeleteNetwork(
                            project.id,
                            checkNotNull(network),
                            requestId,
                        )
                        RfAssetKind.SITE -> RfAssetMutationCommand.DeleteSite(
                            project.id,
                            checkNotNull(site),
                            deleteContainedSectors = true,
                            requestId = requestId,
                        )
                        RfAssetKind.SECTOR -> RfAssetMutationCommand.DeleteSector(
                            project.id,
                            checkNotNull(sectorSite).id,
                            checkNotNull(sector),
                            requestId,
                        )
                        RfAssetKind.RECEIVER -> RfAssetMutationCommand.DeleteReceiver(
                            project.id,
                            checkNotNull(receiver),
                            requestId,
                        )
                    }
                    onMutate(command)
                },
            )
        }
    }
}

@Composable
private fun RfAssetSummary(project: PlannerProject) {
    val sectorCount = project.sites.sumOf { it.sectors.size }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${rfCountLabel(project.networks.size, "network", "networks")} · " +
                "${rfCountLabel(project.sites.size, "site", "sites")} · " +
                "${rfCountLabel(sectorCount, "sector", "sectors")} · " +
                rfCountLabel(project.receivers.size, "receiver", "receivers"),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RfAssetCard(
    title: String,
    subtitle: String,
    metadata: String,
    compatibilityNote: String? = null,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                compatibilityNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("edit_rf_asset")) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit $title")
            }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("delete_rf_asset")) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete $title")
            }
        }
    }
}

@Composable
private fun EmptyRfAssetMessage(message: String) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RfAssetPrerequisiteMessage(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NetworkEditorDialog(
    existing: RfNetwork?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RfNetworkInput) -> Unit,
) {
    var name by rememberSaveable(existing?.id, existing?.hashCode()) { mutableStateOf(existing?.name.orEmpty()) }
    var frequency by rememberSaveable(existing?.id, existing?.hashCode()) {
        mutableStateOf(existing?.downlinkFrequencyMHz?.toString().orEmpty())
    }
    var bandwidth by rememberSaveable(existing?.id, existing?.hashCode()) {
        mutableStateOf(existing?.bandwidthMHz?.toString().orEmpty())
    }
    var systemName by rememberSaveable(existing?.id, existing?.hashCode()) {
        mutableStateOf((existing?.system ?: RadioSystem.GENERIC).name)
    }
    var active by rememberSaveable(existing?.id, existing?.hashCode()) {
        mutableStateOf(existing?.active ?: true)
    }
    var error by remember { mutableStateOf<String?>(null) }
    RfEditorDialog(
        title = if (existing == null) "Add Network" else "Edit Network",
        isSaving = isSaving,
        errorMessage = error,
        hasUnsavedChanges = name != existing?.name.orEmpty() ||
            frequency != existing?.downlinkFrequencyMHz?.toString().orEmpty() ||
            bandwidth != existing?.bandwidthMHz?.toString().orEmpty() ||
            systemName != (existing?.system ?: RadioSystem.GENERIC).name ||
            active != (existing?.active ?: true),
        onDismiss = onDismiss,
        onSave = {
            runCatching {
                RfNetworkInput(
                    name = name,
                    system = RadioSystem.valueOf(systemName),
                    downlinkFrequencyMHz = FrequencyMHz(requiredDouble(frequency, "Frequency")),
                    bandwidthMHz = BandwidthMHz(requiredDouble(bandwidth, "Bandwidth")),
                    active = active,
                )
            }.onSuccess(onSubmit).onFailure { error = it.message ?: "Check the network fields." }
        },
    ) {
        EditorTextField(name, { name = it }, "Network name", testTag = "rf_network_name_field")
        EnumPicker(
            label = "Radio system",
            selected = RadioSystem.valueOf(systemName),
            values = RadioSystem.entries,
            display = ::radioSystemDisplayName,
            onSelected = { systemName = it.name },
        )
        EditorTextField(frequency, { frequency = it }, "Downlink frequency (MHz)", KeyboardType.Decimal)
        EditorTextField(bandwidth, { bandwidth = it }, "Bandwidth (MHz)", KeyboardType.Decimal)
        BooleanField("Active network", active) { active = it }
    }
}

@Composable
private fun SiteEditorDialog(
    existing: RadioSite?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RfSiteInput) -> Unit,
) {
    val stateKey = existing?.hashCode()
    var name by rememberSaveable(existing?.id, stateKey) { mutableStateOf(existing?.name.orEmpty()) }
    var latitude by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.location?.latitude?.toString().orEmpty())
    }
    var longitude by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.location?.longitude?.toString().orEmpty())
    }
    var elevation by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.groundElevationM?.toString().orEmpty())
    }
    var towerHeight by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.towerHeightM?.toString().orEmpty())
    }
    var notes by rememberSaveable(existing?.id, stateKey) { mutableStateOf(existing?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    RfEditorDialog(
        title = if (existing == null) "Add Site" else "Edit Site",
        isSaving = isSaving,
        errorMessage = error,
        hasUnsavedChanges = name != existing?.name.orEmpty() ||
            latitude != existing?.location?.latitude?.toString().orEmpty() ||
            longitude != existing?.location?.longitude?.toString().orEmpty() ||
            elevation != existing?.groundElevationM?.toString().orEmpty() ||
            towerHeight != existing?.towerHeightM?.toString().orEmpty() ||
            notes != existing?.notes.orEmpty(),
        onDismiss = onDismiss,
        onSave = {
            runCatching {
                RfSiteInput(
                    name = name,
                    location = GeoPoint(
                        latitude = requiredDouble(latitude, "Latitude"),
                        longitude = requiredDouble(longitude, "Longitude"),
                    ),
                    groundElevationM = optionalDouble(elevation, "Ground elevation"),
                    towerHeightM = optionalDouble(towerHeight, "Tower height"),
                    notes = notes,
                )
            }.onSuccess(onSubmit).onFailure { error = it.message ?: "Check the site fields." }
        },
    ) {
        EditorTextField(name, { name = it }, "Site name")
        EditorTextField(latitude, { latitude = it }, "Latitude (degrees)", KeyboardType.Decimal)
        EditorTextField(longitude, { longitude = it }, "Longitude (degrees)", KeyboardType.Decimal)
        EditorTextField(elevation, { elevation = it }, "Ground elevation (m, optional)", KeyboardType.Decimal)
        EditorTextField(towerHeight, { towerHeight = it }, "Tower height (m, optional)", KeyboardType.Decimal)
        EditorTextField(notes, { notes = it }, "Notes", singleLine = false)
    }
}

@Composable
private fun SectorEditorDialog(
    existing: Sector?,
    existingSite: RadioSite?,
    sites: List<RadioSite>,
    networks: List<RfNetwork>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, RfSectorInput) -> Unit,
) {
    val stateKey = existing?.hashCode()
    var siteId by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existingSite?.id ?: sites.firstOrNull()?.id.orEmpty())
    }
    var name by rememberSaveable(existing?.id, stateKey) { mutableStateOf(existing?.name.orEmpty()) }
    var networkId by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.networkId ?: networks.firstOrNull()?.id)
    }
    var frequency by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(
            existing?.frequencyMHz?.toString()
                ?: networks.firstOrNull { it.id == networkId }?.downlinkFrequencyMHz?.toString().orEmpty(),
        )
    }
    var azimuth by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.azimuthDegrees?.let(::canonicalAzimuth)?.toString() ?: "0.0")
    }
    var tilt by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.electricalTiltDegrees?.toString() ?: "0.0")
    }
    var height by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.antennaHeightM?.toString() ?: "30.0")
    }
    var power by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.transmitPowerDbm?.toString() ?: "43.0")
    }
    var gain by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.antennaGainDbi?.toString() ?: "0.0")
    }
    var loss by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.feederLossDb?.toString() ?: "0.0")
    }
    var active by rememberSaveable(existing?.id, stateKey) { mutableStateOf(existing?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    RfEditorDialog(
        title = if (existing == null) "Add Sector" else "Edit Sector",
        isSaving = isSaving,
        errorMessage = error,
        hasUnsavedChanges = siteId != (existingSite?.id ?: sites.firstOrNull()?.id.orEmpty()) ||
            name != existing?.name.orEmpty() ||
            networkId != (existing?.networkId ?: networks.firstOrNull()?.id) ||
            frequency != (
                existing?.frequencyMHz?.toString()
                    ?: networks.firstOrNull { it.id == networkId }?.downlinkFrequencyMHz?.toString().orEmpty()
                ) ||
            azimuth != (existing?.azimuthDegrees?.let(::canonicalAzimuth)?.toString() ?: "0.0") ||
            tilt != (existing?.electricalTiltDegrees?.toString() ?: "0.0") ||
            height != (existing?.antennaHeightM?.toString() ?: "30.0") ||
            power != (existing?.transmitPowerDbm?.toString() ?: "43.0") ||
            gain != (existing?.antennaGainDbi?.toString() ?: "0.0") ||
            loss != (existing?.feederLossDb?.toString() ?: "0.0") ||
            active != (existing?.active ?: true),
        onDismiss = onDismiss,
        onSave = {
            runCatching {
                RfSectorInput(
                    name = name,
                    active = active,
                    networkId = networkId,
                    frequencyMHz = FrequencyMHz(requiredDouble(frequency, "Frequency")),
                    azimuthDegrees = AzimuthDegrees(canonicalAzimuth(requiredDouble(azimuth, "Azimuth"))),
                    electricalTiltDegrees = TiltDegrees(requiredDouble(tilt, "Electrical tilt")),
                    antennaHeightM = HeightM(requiredDouble(height, "Antenna height")),
                    transmitPowerDbm = PowerDbm(requiredDouble(power, "Transmit power")),
                    antennaGainDbi = GainDbi(requiredDouble(gain, "Antenna gain")),
                    feederLossDb = LossDb(requiredDouble(loss, "Feeder loss")),
                )
            }.onSuccess { onSubmit(siteId, it) }
                .onFailure { error = it.message ?: "Check the sector fields." }
        },
    ) {
        if (existing == null) {
            EntityPicker("Site", siteId, sites, RadioSite::id, RadioSite::name) { siteId = it }
        } else {
            ReadOnlyValue("Site", checkNotNull(existingSite).name)
        }
        EditorTextField(name, { name = it }, "Sector name")
        NullableNetworkPicker(networkId, networks) { selected ->
            networkId = selected
            if (frequency.isBlank()) {
                frequency = networks.firstOrNull { it.id == selected }?.downlinkFrequencyMHz?.toString().orEmpty()
            }
        }
        EditorTextField(frequency, { frequency = it }, "Frequency (MHz)", KeyboardType.Decimal)
        EditorTextField(azimuth, { azimuth = it }, "Azimuth (degrees)", KeyboardType.Decimal)
        EditorTextField(tilt, { tilt = it }, "Electrical tilt (degrees)", KeyboardType.Decimal)
        EditorTextField(height, { height = it }, "Antenna height (m)", KeyboardType.Decimal)
        EditorTextField(power, { power = it }, "Transmit power (dBm)", KeyboardType.Decimal)
        EditorTextField(gain, { gain = it }, "Antenna gain (dBi)", KeyboardType.Decimal)
        EditorTextField(loss, { loss = it }, "Feeder loss (dB)", KeyboardType.Decimal)
        BooleanField("Active sector", active) { active = it }
    }
}

@Composable
private fun ReceiverEditorDialog(
    existing: Receiver?,
    networks: List<RfNetwork>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RfReceiverInput) -> Unit,
) {
    val stateKey = existing?.hashCode()
    var name by rememberSaveable(existing?.id, stateKey) { mutableStateOf(existing?.name.orEmpty()) }
    var networkId by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.networkId ?: networks.firstOrNull()?.id.orEmpty())
    }
    var latitude by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.location?.latitude?.value?.toString().orEmpty())
    }
    var longitude by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.location?.longitude?.value?.toString().orEmpty())
    }
    var height by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.antennaHeightM?.value?.toString() ?: "1.5")
    }
    var gain by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.antennaGainDbi?.value?.toString() ?: "0.0")
    }
    var loss by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.systemLossDb?.value?.toString() ?: "0.0")
    }
    var sensitivity by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.sensitivityDbm?.value?.toString() ?: "-100.0")
    }
    var noiseFigure by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.noiseFigureDb?.value?.toString() ?: "0.0")
    }
    var azimuth by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.azimuthDegrees?.value?.toString() ?: "0.0")
    }
    var tilt by rememberSaveable(existing?.id, stateKey) {
        mutableStateOf(existing?.electricalTiltDegrees?.value?.toString() ?: "0.0")
    }
    var notes by rememberSaveable(existing?.id, stateKey) { mutableStateOf(existing?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    RfEditorDialog(
        title = if (existing == null) "Add Receiver" else "Edit Receiver",
        isSaving = isSaving,
        errorMessage = error,
        hasUnsavedChanges = name != existing?.name.orEmpty() ||
            networkId != (existing?.networkId ?: networks.firstOrNull()?.id.orEmpty()) ||
            latitude != existing?.location?.latitude?.value?.toString().orEmpty() ||
            longitude != existing?.location?.longitude?.value?.toString().orEmpty() ||
            height != (existing?.antennaHeightM?.value?.toString() ?: "1.5") ||
            gain != (existing?.antennaGainDbi?.value?.toString() ?: "0.0") ||
            loss != (existing?.systemLossDb?.value?.toString() ?: "0.0") ||
            sensitivity != (existing?.sensitivityDbm?.value?.toString() ?: "-100.0") ||
            noiseFigure != (existing?.noiseFigureDb?.value?.toString() ?: "0.0") ||
            azimuth != (existing?.azimuthDegrees?.value?.toString() ?: "0.0") ||
            tilt != (existing?.electricalTiltDegrees?.value?.toString() ?: "0.0") ||
            notes != existing?.notes.orEmpty(),
        onDismiss = onDismiss,
        onSave = {
            runCatching {
                RfReceiverInput(
                    name = name,
                    networkId = networkId,
                    location = GeoCoordinate(
                        LatitudeDegrees(requiredDouble(latitude, "Latitude")),
                        LongitudeDegrees(requiredDouble(longitude, "Longitude")),
                    ),
                    antennaHeightM = HeightM(requiredDouble(height, "Antenna height")),
                    antennaGainDbi = GainDbi(requiredDouble(gain, "Antenna gain")),
                    systemLossDb = LossDb(requiredDouble(loss, "System loss")),
                    sensitivityDbm = PowerDbm(requiredDouble(sensitivity, "Sensitivity")),
                    noiseFigureDb = LossDb(requiredDouble(noiseFigure, "Noise figure")),
                    azimuthDegrees = AzimuthDegrees(canonicalAzimuth(requiredDouble(azimuth, "Azimuth"))),
                    electricalTiltDegrees = TiltDegrees(requiredDouble(tilt, "Electrical tilt")),
                    notes = notes,
                )
            }.onSuccess(onSubmit).onFailure { error = it.message ?: "Check the receiver fields." }
        },
    ) {
        EditorTextField(name, { name = it }, "Receiver name")
        EntityPicker("Network", networkId, networks, RfNetwork::id, RfNetwork::name) { networkId = it }
        existing?.takeIf { it.networkProfiles.isNotEmpty() }?.let { receiver ->
            PreservedReceiverProfilesNotice(receiver, networks)
        }
        EditorTextField(latitude, { latitude = it }, "Latitude (degrees)", KeyboardType.Decimal)
        EditorTextField(longitude, { longitude = it }, "Longitude (degrees)", KeyboardType.Decimal)
        EditorTextField(height, { height = it }, "Antenna height (m)", KeyboardType.Decimal)
        EditorTextField(gain, { gain = it }, "Antenna gain (dBi)", KeyboardType.Decimal)
        EditorTextField(loss, { loss = it }, "System loss (dB)", KeyboardType.Decimal)
        EditorTextField(sensitivity, { sensitivity = it }, "Sensitivity (dBm)", KeyboardType.Decimal)
        EditorTextField(noiseFigure, { noiseFigure = it }, "Noise figure (dB)", KeyboardType.Decimal)
        EditorTextField(azimuth, { azimuth = it }, "Azimuth (degrees)", KeyboardType.Decimal)
        EditorTextField(tilt, { tilt = it }, "Electrical tilt (degrees)", KeyboardType.Decimal)
        EditorTextField(notes, { notes = it }, "Notes", singleLine = false)
    }
}

@Composable
private fun PreservedReceiverProfilesNotice(
    receiver: Receiver,
    networks: List<RfNetwork>,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Preserved compatibility references (read-only)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                profileNetworkLabels(receiver, networks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "These per-network profiles are preserved when this receiver is saved, but they cannot " +
                    "be reassigned in this Android editor. They can block network deletion; in this phase, " +
                    "removing a profile-only reference requires deleting the receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun RfEditorUnavailableDialog(
    assetLabel: String,
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$assetLabel Unavailable") },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("close_unavailable_rf_editor"),
            ) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun RfEditorDialog(
    title: String,
    isSaving: Boolean,
    errorMessage: String?,
    hasUnsavedChanges: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var showDiscardConfirmation by rememberSaveable(title) { mutableStateOf(false) }
    fun requestDismiss() {
        if (!isSaving) {
            if (hasUnsavedChanges) showDiscardConfirmation = true else onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .widthIn(max = 720.dp)
                .testTag("rf_asset_editor"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
                errorMessage?.let { message ->
                    HorizontalDivider()
                    FormError(
                        message = message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = ::requestDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("save_rf_asset_button"),
                    ) {
                        Text(if (isSaving) "Saving…" else "Save")
                    }
                }
            }
        }
    }
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your local draft changes have not been saved.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_discard_rf_draft"),
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Keep Editing")
                }
            },
        )
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    testTag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
    )
}

@Composable
private fun BooleanField(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> EnumPicker(
    label: String,
    selected: T,
    values: List<T>,
    display: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(display(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(display(value)) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> EntityPicker(
    label: String,
    selectedId: String,
    values: List<T>,
    id: (T) -> String,
    display: (T) -> String,
    onSelected: (String) -> Unit,
) {
    if (values.isEmpty()) {
        ReadOnlyValue(label, "No options available")
        return
    }
    val selected = values.firstOrNull { id(it) == selectedId } ?: values.first()
    EnumPicker(label, selected, values, display) { onSelected(id(it)) }
}

@Composable
private fun NullableNetworkPicker(
    selectedId: String?,
    networks: List<RfNetwork>,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = networks.firstOrNull { it.id == selectedId }?.name ?: "Unassigned"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Network", style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Unassigned") }, onClick = {
                    expanded = false
                    onSelected(null)
                })
                networks.forEach { network ->
                    DropdownMenuItem(text = { Text(network.name) }, onClick = {
                        expanded = false
                        onSelected(network.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FormError(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("rf_form_error"),
    ) {
        Text(
            message,
            modifier = Modifier.padding(10.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DeleteRfAssetDialog(
    kind: RfAssetKind,
    entityName: String,
    sectorCount: Int,
    receiverCount: Int,
    preservedProfileReferenceCount: Int,
    blocked: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Delete ${kind.name.lowercase().replaceFirstChar(Char::uppercase)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delete \"$entityName\" from this local project?")
                when {
                    blocked -> Text(
                        blockedNetworkDeletionMessage(sectorCount, receiverCount),
                        color = MaterialTheme.colorScheme.error,
                    )
                    kind == RfAssetKind.SITE && sectorCount > 0 -> Text(
                        "Deleting this site also deletes " +
                            "${rfCountLabel(sectorCount, "contained sector", "contained sectors")} " +
                            "in the same transaction.",
                    )
                    else -> Text("This action changes only the selected project document.")
                }
                if (preservedProfileReferenceCount > 0) {
                    val profileVerb = if (preservedProfileReferenceCount == 1) "includes" else "include"
                    Text(
                        "${rfCountLabel(preservedProfileReferenceCount, "receiver", "receivers")} " +
                            "$profileVerb preserved read-only compatibility references to this network. " +
                            "This Android editor cannot change those profile references; in this phase, " +
                            "removing a profile-only reference requires deleting the receiver.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !blocked && !isSaving,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_delete_rf_asset"),
            ) {
                Text(if (isSaving) "Deleting…" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        },
    )
}

private fun requiredDouble(value: String, label: String): Double = value.trim().toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?: throw IllegalArgumentException("$label requires a finite number.")

private fun optionalDouble(value: String, label: String): Double? = value.trim().takeIf(String::isNotEmpty)
    ?.let { requiredDouble(it, label) }

private fun canonicalAzimuth(value: Double): Double = if (value == 360.0) 0.0 else value

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.3f", value)
    .trimEnd('0')
    .trimEnd('.')

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

private fun rfCountLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"

private fun blockedNetworkDeletionMessage(sectorCount: Int, receiverCount: Int): String {
    val references = buildList {
        if (sectorCount > 0) add(rfCountLabel(sectorCount, "sector", "sectors"))
        if (receiverCount > 0) add(rfCountLabel(receiverCount, "receiver", "receivers"))
    }
    val verb = if (sectorCount + receiverCount == 1) "references" else "reference"
    return "Deletion is blocked because ${references.joinToString(" and ")} $verb this network. " +
        "Resolve those references first."
}

private fun receiverCompatibilitySummary(receiver: Receiver, networks: List<RfNetwork>): String? =
    if (receiver.networkProfiles.isEmpty()) {
        null
    } else {
        "Preserved profiles (read-only): ${profileNetworkLabels(receiver, networks)}"
    }

private fun profileNetworkLabels(receiver: Receiver, networks: List<RfNetwork>): String {
    val labels = receiver.networkProfiles.take(3).map { profile ->
        networks.firstOrNull { it.id == profile.networkId }?.name
            ?: "Missing network (${profile.networkId})"
    }
    val remainder = receiver.networkProfiles.size - labels.size
    return buildString {
        append(labels.joinToString())
        if (remainder > 0) append(" +$remainder more")
    }
}

private fun newRfRequestId(): String = "rf-ui-${UUID.randomUUID()}"

private fun radioSystemDisplayName(system: RadioSystem): String = when (system) {
    RadioSystem.GENERIC -> "Generic"
    RadioSystem.FM_BROADCAST -> "FM Broadcast"
    RadioSystem.TV_BROADCAST -> "TV Broadcast"
    RadioSystem.LTE -> "LTE"
    RadioSystem.NR_5G -> "5G NR"
    RadioSystem.LAND_MOBILE -> "Land Mobile"
    RadioSystem.FWA -> "Fixed Wireless Access"
    RadioSystem.AIR_TO_GROUND -> "Air-to-Ground"
}
