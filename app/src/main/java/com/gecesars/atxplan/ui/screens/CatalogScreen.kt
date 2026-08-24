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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone

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
        "Offline canvas; MapLibre, tiles, and geographic editing arrive in Stage 2.",
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
        "Planned",
        StatusTone.WARNING,
        "Regional packages, provenance, and an inconclusive result when official data is missing.",
    ),
)

@Composable
fun CatalogScreen() {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useTwoColumns = !largeText && maxWidth >= 600.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
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
                            body = "The APK will not bundle tens of gigabytes. Each project requests only the required tiles and records their origin.",
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
                            body = "The APK will not bundle tens of gigabytes. Each project requests only the required tiles and records their origin.",
                        )
                    }
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
