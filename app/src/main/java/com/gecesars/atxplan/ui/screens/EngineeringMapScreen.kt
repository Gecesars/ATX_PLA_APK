package com.gecesars.atxplan.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
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
    val sites = project?.sites.orEmpty()
    val largeText = LocalDensity.current.fontScale >= 1.3f
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useTwoColumnSiteRows = !largeText && maxWidth >= 600.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(
                    title = project?.name ?: "No Project Selected",
                    subtitle = "Offline local geometry for checking positions and azimuths before downloading basemaps and terrain.",
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(
                        "${sites.size} ${if (sites.size == 1) "Site" else "Sites"}",
                        if (sites.isEmpty()) StatusTone.INFO else StatusTone.POSITIVE,
                    )
                    StatusPill("Azimuths", StatusTone.INFO)
                    StatusPill("Basemap: Stage 2", StatusTone.WARNING)
                }
            }
            item { TechnicalMapCard(sites = sites) }
            item { Text("Project Sites", style = MaterialTheme.typography.titleLarge) }
            if (sites.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = null)
                            Spacer(Modifier.height(6.dp))
                            Text("The selected project does not have any sites yet.")
                        }
                    }
                }
            } else if (useTwoColumnSiteRows) {
                items(sites.chunked(2), key = { row -> row.first().id }) { rowSites ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        rowSites.forEach { site ->
                            SiteMapRow(site = site, modifier = Modifier.weight(1f))
                        }
                        if (rowSites.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(sites, key = RadioSite::id) { site -> SiteMapRow(site) }
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
}

@Composable
private fun TechnicalMapCard(sites: List<RadioSite>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val mapHeight = (maxWidth * 0.72f).coerceIn(260.dp, 390.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(AtxDarkBackground),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription =
                            "Engineering map with ${sites.size} sites and their azimuths"
                    },
            ) {
                drawRect(AtxDarkBackground)
                val gridColor = Color.White.copy(alpha = 0.08f)
                val gridStrokeWidth = 0.5.dp.toPx()
                for (index in 1 until 8) {
                    val x = size.width * index / 8f
                    val y = size.height * index / 8f
                    drawLine(
                        gridColor,
                        Offset(x, 0f),
                        Offset(x, size.height),
                        strokeWidth = gridStrokeWidth,
                    )
                    drawLine(
                        gridColor,
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = gridStrokeWidth,
                    )
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
                val plotInsetHorizontal = 24.dp.toPx()
                val plotInsetVertical = 52.dp.toPx()
                val plotWidth = (size.width - 2f * plotInsetHorizontal).coerceAtLeast(1f)
                val plotHeight = (size.height - 2f * plotInsetVertical).coerceAtLeast(1f)
                val haloRadius = 22.dp.toPx()
                val ringRadius = 13.dp.toPx()
                val vectorLength = 28.dp.toPx()
                val vectorStrokeWidth = 2.dp.toPx()
                val centerOutlineRadius = 4.dp.toPx()
                val centerRadius = 2.5.dp.toPx()

                sites.forEachIndexed { index, site ->
                    val x = plotInsetHorizontal +
                        ((site.location.longitude - minLon) / lonSpan * plotWidth).toFloat()
                    val y = plotInsetVertical +
                        ((maxLat - site.location.latitude) / latSpan * plotHeight).toFloat()
                    val point = Offset(x, y)
                    val siteColor = when (index % 3) {
                        0 -> AtxTealLight
                        1 -> AtxAmber
                        else -> AtxSignal
                    }
                    drawCircle(siteColor.copy(alpha = 0.08f), radius = haloRadius, center = point)
                    drawCircle(
                        siteColor.copy(alpha = 0.28f),
                        radius = ringRadius,
                        center = point,
                        style = Stroke(1.dp.toPx()),
                    )
                    site.sectors.filter { it.active }.forEach { sector ->
                        val angle = Math.toRadians(sector.azimuthDegrees)
                        val end = Offset(
                            x = point.x + sin(angle).toFloat() * vectorLength,
                            y = point.y - cos(angle).toFloat() * vectorLength,
                        )
                        drawLine(
                            color = siteColor,
                            start = point,
                            end = end,
                            strokeWidth = vectorStrokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(Color.White, radius = centerOutlineRadius, center = point)
                    drawCircle(siteColor, radius = centerRadius, center = point)
                }
            }
            StatusPill(
                label = "LOCAL • NO TILES",
                tone = StatusTone.INFO,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
}

@Composable
private fun SiteMapRow(site: RadioSite, modifier: Modifier = Modifier) {
    val activeSectors = site.sectors.count { it.active }
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        site.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "$activeSectors of ${site.sectors.size} active",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    String.format(
                        Locale.US,
                        "Lat %.5f°  •  Lon %.5f°",
                        site.location.latitude,
                        site.location.longitude,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
