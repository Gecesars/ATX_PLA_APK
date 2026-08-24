package com.gecesars.atxplan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gecesars.atxplan.ui.theme.AtxAmber
import com.gecesars.atxplan.ui.theme.AtxDanger
import com.gecesars.atxplan.ui.theme.AtxSignal
import com.gecesars.atxplan.ui.theme.AtxTeal

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

enum class StatusTone { POSITIVE, INFO, WARNING, NEGATIVE, NEUTRAL }

@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = when (tone) {
        StatusTone.POSITIVE -> AtxSignal.copy(alpha = 0.16f)
        StatusTone.INFO -> AtxTeal.copy(alpha = 0.16f)
        StatusTone.WARNING -> AtxAmber.copy(alpha = 0.22f)
        StatusTone.NEGATIVE -> AtxDanger.copy(alpha = 0.14f)
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (tone) {
        StatusTone.POSITIVE -> if (darkTheme) Color(0xFF70DBA5) else Color(0xFF146C43)
        StatusTone.INFO -> if (darkTheme) Color(0xFF74D7CE) else AtxTeal
        StatusTone.WARNING -> if (darkTheme) Color(0xFFFFD166) else Color(0xFF765500)
        StatusTone.NEGATIVE -> if (darkTheme) MaterialTheme.colorScheme.error else AtxDanger
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 34.dp)
                    .background(accent, RoundedCornerShape(100.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StorageErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Local storage: $message",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            onRetry?.let { retry ->
                TextButton(
                    onClick = retry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
