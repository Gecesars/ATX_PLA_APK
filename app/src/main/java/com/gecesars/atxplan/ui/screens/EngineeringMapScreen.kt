package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.ui.components.ScreenHeader
import com.gecesars.atxplan.ui.components.StatusPill
import com.gecesars.atxplan.ui.components.StatusTone
import com.gecesars.atxplan.ui.theme.AtxAmber
import com.gecesars.atxplan.ui.theme.AtxDarkBackground
import com.gecesars.atxplan.ui.theme.AtxSignal
import com.gecesars.atxplan.ui.theme.AtxTealLight
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun EngineeringMapScreen(project: PlannerProject?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "Offline Engineering Map",
                subtitle = "Local geometry for checking positions and azimuths before downloading basemaps and terrain.",
            )
        }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill("Sites", StatusTone.POSITIVE)
                StatusPill("Azimuths", StatusTone.INFO)
                StatusPill("Basemap: Stage 2", StatusTone.WARNING)
            }
        }
        item {
            TechnicalMapCard(sites = project?.sites.orEmpty())
        }
        item {
            Text("Project Sites", style = MaterialTheme.typography.titleLarge)
        }
        if (project?.sites.isNullOrEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null)
                        Spacer(Modifier.height(8.dp))
                        Text("The selected project does not have any sites yet.")
                    }
                }
            }
        } else {
            items(project!!.sites, key = RadioSite::id) { site -> SiteMapRow(site) }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Layers, contentDescription = null)
                    Text(
                        "MapLibre, regional caching, DEM, clutter, and coverage layers are planned with explicit licensing, attribution, and disk budgets.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TechnicalMapCard(sites: List<RadioSite>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(390.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AtxDarkBackground),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Engineering map with ${sites.size} sites and their azimuths"
                },
        ) {
            drawRect(AtxDarkBackground)
            val gridColor = Color.White.copy(alpha = 0.08f)
            for (index in 1 until 8) {
                val x = size.width * index / 8f
                val y = size.height * index / 8f
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (sites.isEmpty()) return@Canvas

            val longitudes = sites.map { it.location.longitude }
            val latitudes = sites.map { it.location.latitude }
            val centerLon = (longitudes.min() + longitudes.max()) / 2.0
            val centerLat = (latitudes.min() + latitudes.max()) / 2.0
            val lonSpan = (longitudes.max() - longitudes.min()).coerceAtLeast(0.03) * 1.35
            val latSpan = (latitudes.max() - latitudes.min()).coerceAtLeast(0.03) * 1.35
            val minLon = centerLon - lonSpan / 2.0
            val maxLat = centerLat + latSpan / 2.0

            sites.forEachIndexed { index, site ->
                val x = ((site.location.longitude - minLon) / lonSpan * size.width).toFloat()
                val y = ((maxLat - site.location.latitude) / latSpan * size.height).toFloat()
                val point = Offset(x, y)
                val siteColor = when (index % 3) {
                    0 -> AtxTealLight
                    1 -> AtxAmber
                    else -> AtxSignal
                }
                drawCircle(siteColor.copy(alpha = 0.08f), radius = 72f, center = point)
                drawCircle(siteColor.copy(alpha = 0.28f), radius = 42f, center = point, style = Stroke(2.5f))
                site.sectors.filter { it.active }.forEach { sector ->
                    val angle = Math.toRadians(sector.azimuthDegrees)
                    val end = Offset(
                        x = point.x + sin(angle).toFloat() * 86f,
                        y = point.y - cos(angle).toFloat() * 86f,
                    )
                    drawLine(
                        color = siteColor,
                        start = point,
                        end = end,
                        strokeWidth = 7f,
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(Color.White, radius = 12f, center = point)
                drawCircle(siteColor, radius = 7f, center = point)
            }
        }
        StatusPill(
            label = "LOCAL • NO TILES",
            tone = StatusTone.INFO,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
                .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(AtxTealLight, CircleShape))
            Text(
                "marker = site  •  line = azimuth",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SiteMapRow(site: RadioSite) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(site.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    String.format(
                        Locale.US,
                        "%.5f, %.5f",
                        site.location.latitude,
                        site.location.longitude,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "${site.sectors.count { it.active }} TX",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
