package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.OfflineBolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.ui.AppUiState
import com.gecesars.atxplan.ui.components.MetricCard
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.theme.AtxAmber
import com.gecesars.atxplan.ui.theme.AtxNavy
import com.gecesars.atxplan.ui.theme.AtxSignal
import com.gecesars.atxplan.ui.theme.AtxTealLight

@Composable
fun DashboardScreen(
    uiState: AppUiState,
    onOpenProjects: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenStudies: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            ScreenHeader(
                title = "Engineering Center",
                subtitle = "Projects, maps, and RF calculations on an offline-first foundation.",
            )
        }
        item {
            HeroCard(project = uiState.selectedProject, onOpenStudies = onOpenStudies)
        }
        if (uiState.isLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            item {
                val project = uiState.selectedProject
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        value = uiState.catalog.projects.size.toString(),
                        label = countLabel(uiState.catalog.projects.size, "local project", "local projects"),
                        modifier = Modifier.weight(1f),
                        accent = AtxSignal,
                    )
                    MetricCard(
                        value = (project?.sites?.size ?: 0).toString(),
                        label = countLabel(project?.sites?.size ?: 0, "active site", "active sites"),
                        modifier = Modifier.weight(1f),
                        accent = AtxAmber,
                    )
                }
            }
            item {
                MetricCard(
                    value = (uiState.selectedProject?.studies?.size ?: 0).toString(),
                    label = countLabel(
                        uiState.selectedProject?.studies?.size ?: 0,
                        "configured study",
                        "configured studies",
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text("Quick Actions", style = MaterialTheme.typography.titleLarge)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    title = "Manage Projects",
                    subtitle = "Create, select, and review your local workspace",
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    onClick = onOpenProjects,
                )
                QuickAction(
                    title = "Open Engineering Map",
                    subtitle = "Inspect sites and azimuths without a network connection",
                    icon = { Icon(Icons.Outlined.Map, contentDescription = null) },
                    onClick = onOpenMap,
                )
                QuickAction(
                    title = "Calculate Link Budget",
                    subtitle = "FSPL, EIRP, received power, noise, and Fresnel clearance",
                    icon = { Icon(Icons.Outlined.Calculate, contentDescription = null) },
                    onClick = onOpenStudies,
                )
            }
        }
        item {
            FoundationStatusCard()
        }
    }
}

@Composable
private fun HeroCard(project: PlannerProject?, onOpenStudies: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AtxNavy),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.OfflineBolt, contentDescription = null, tint = AtxTealLight)
                Spacer(Modifier.width(8.dp))
                Text(
                    "WORKS OFFLINE",
                    color = AtxTealLight,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = project?.name ?: "Create Your First Project",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = project?.let {
                    listOf(
                        countLabel(it.networks.size, "network", "networks"),
                        countLabel(it.sites.size, "site", "sites"),
                        countLabel(it.studies.size, "study", "studies"),
                    ).joinToString(" • ")
                } ?: "The local catalog is ready for networks, sites, and scenarios.",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onOpenStudies) {
                Text("Run Quick Calculation")
            }
        }
    }
}

@Composable
private fun QuickAction(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FoundationStatusCard() {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Android Foundation", style = MaterialTheme.typography.titleMedium)
                StatusPill("Stage 0", StatusTone.INFO)
            }
            Text(
                "Atomic persistence, adaptive navigation, typed RF models, and unit-testable calculations.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Standards-based engines, basemaps, and raster coverage will ship only after their data and parity gates pass.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun countLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"
