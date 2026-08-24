package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Engineering Center",
                subtitle = "Projects, maps, and RF calculations on an offline-first foundation.",
            )
        }
        item {
            HeroCard(uiState = uiState, onOpenStudies = onOpenStudies)
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
            item { DashboardMetrics(uiState) }
        }
        item {
            Text(
                "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
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
private fun DashboardMetrics(uiState: AppUiState) {
    val projectCount = uiState.catalog.projects.size
    val siteCount = uiState.selectedProject?.sites?.size ?: 0
    val studyCount = uiState.selectedProject?.studies?.size ?: 0
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useSingleRow = maxWidth >= 350.dp && fontScale < 1.3f
        if (useSingleRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard(
                    value = projectCount.toString(),
                    label = pluralLabel(projectCount, "project", "projects"),
                    modifier = Modifier.weight(1f),
                    accent = AtxSignal,
                )
                MetricCard(
                    value = siteCount.toString(),
                    label = pluralLabel(siteCount, "site", "sites"),
                    modifier = Modifier.weight(1f),
                    accent = AtxAmber,
                )
                MetricCard(
                    value = studyCount.toString(),
                    label = pluralLabel(studyCount, "study", "studies"),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricCard(
                        value = projectCount.toString(),
                        label = pluralLabel(projectCount, "project", "projects"),
                        modifier = Modifier.weight(1f),
                        accent = AtxSignal,
                    )
                    MetricCard(
                        value = siteCount.toString(),
                        label = pluralLabel(siteCount, "site", "sites"),
                        modifier = Modifier.weight(1f),
                        accent = AtxAmber,
                    )
                }
                MetricCard(
                    value = studyCount.toString(),
                    label = pluralLabel(studyCount, "study", "studies"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(uiState: AppUiState, onOpenStudies: () -> Unit) {
    val project = uiState.selectedProject
    val title = when {
        uiState.isLoading -> "Opening Local Catalog"
        uiState.storageProblem != null -> "Catalog Needs Attention"
        project != null -> project.name
        else -> "Create Your First Project"
    }
    val detail = when {
        uiState.isLoading -> "Reading validated project data from private device storage."
        uiState.storageProblem != null ->
            "Project changes are blocked until the local catalog is recovered."
        project != null -> listOf(
            countLabel(project.networks.size, "network", "networks"),
            countLabel(project.sites.size, "site", "sites"),
            countLabel(project.receivers.size, "receiver", "receivers"),
        ).joinToString(" • ")
        else -> "The local catalog is ready for networks, sites, and receivers."
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = AtxNavy),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
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
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = onOpenStudies,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FoundationStatusCard() {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Android Foundation", style = MaterialTheme.typography.titleMedium)
                StatusPill("Stage 1", StatusTone.INFO)
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

private fun pluralLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
