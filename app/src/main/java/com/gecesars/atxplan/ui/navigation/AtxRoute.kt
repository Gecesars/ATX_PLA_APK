package com.gecesars.atxplan.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer

/**
 * Stable, serializable keys for ATX Plan destinations.
 *
 * The complete route is persisted as one bounded stable-ID string. This avoids coupling Android
 * saved state to Kotlin class names and lets a newer, malformed, or removed route decode into a
 * safe fallback instead of failing sealed-polymorphic deserialization.
 */
@Serializable(with = AtxRouteStableIdSerializer::class)
sealed interface AtxRoute : NavKey {
    val stableId: String

    companion object {
        /** Converts an untrusted persisted or external identifier into a bounded route key. */
        fun fromStableId(stableId: String?): AtxRoute {
            if (stableId == null || stableId.length > MAX_PERSISTED_ROUTE_ID_LENGTH) {
                return UnsupportedRoute(stableId.orEmpty())
            }
            return when (stableId) {
                DashboardRoute.stableId -> DashboardRoute
                ProjectsRoute.stableId -> ProjectsRoute
                MapRoute.stableId -> MapRoute
                StudiesRoute.stableId -> StudiesRoute
                CatalogRoute.stableId -> CatalogRoute
                else -> parseRfPathEditorRoute(stableId) ?: UnsupportedRoute(stableId)
            }
        }

        /** Creates a nested editor route, or Dashboard when the project ID is not safe to persist. */
        fun rfPathEditor(projectId: String?): AtxRoute =
            if (projectId != null && projectId.isValidRouteProjectId()) {
                RfPathEditorRoute(projectId)
            } else {
                DashboardRoute
            }
    }
}

@Serializable
data object DashboardRoute : AtxRoute {
    override val stableId = "dashboard"
}

@Serializable
data object ProjectsRoute : AtxRoute {
    override val stableId = "projects"
}

@Serializable
data object MapRoute : AtxRoute {
    override val stableId = "map"
}

@Serializable
data object StudiesRoute : AtxRoute {
    override val stableId = "studies"
}

@Serializable
data object CatalogRoute : AtxRoute {
    override val stableId = "catalog"
}

/**
 * Nested editor destination. Only [projectId] is navigation state; repositories resolve the full
 * project after restoration.
 */
@Serializable
data class RfPathEditorRoute(
    val projectId: String,
) : AtxRoute {
    internal val hasValidProjectId: Boolean
        get() = projectId.isValidRouteProjectId()

    override val stableId: String
        get() = if (hasValidProjectId) "$RF_PATH_EDITOR_PREFIX$projectId" else DashboardRoute.stableId
}

/** A bounded unknown identifier that the shell renders through its safe fallback branch. */
class UnsupportedRoute internal constructor(
    rawStableId: String,
) : AtxRoute {
    override val stableId = rawStableId.take(MAX_UNKNOWN_ROUTE_ID_LENGTH)
}

/** Serializer used by Navigation 3 for every [AtxRoute] element in the saved back stack. */
object AtxRouteStableIdSerializer : KSerializer<AtxRoute> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "com.gecesars.atxplan.ui.navigation.AtxRouteStableId",
        kind = PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: AtxRoute) {
        encoder.encodeString(value.persistedStableId())
    }

    override fun deserialize(decoder: Decoder): AtxRoute =
        AtxRoute.fromStableId(decoder.decodeString())
}

internal fun AtxRoute.supportedOrDashboard(): AtxRoute = when (this) {
    DashboardRoute,
    ProjectsRoute,
    MapRoute,
    StudiesRoute,
    CatalogRoute,
    -> this

    is RfPathEditorRoute -> if (hasValidProjectId) this else DashboardRoute
    is UnsupportedRoute -> DashboardRoute
}

/**
 * A subtype-preserving form of Navigation 3's saveable back stack recipe.
 *
 * [rememberSerializable] saves the stack through Android saved instance state, while
 * [NavBackStack] keeps mutations observable by Compose.
 */
@Composable
internal fun rememberAtxNavBackStack(
    initialRoute: AtxRoute = DashboardRoute,
): NavBackStack<AtxRoute> = rememberSerializable(
    serializer = NavBackStackSerializer(serializer<AtxRoute>()),
) {
    NavBackStack(initialRoute.supportedOrDashboard())
}

internal val NavBackStack<AtxRoute>.activeRoute: AtxRoute
    get() = lastOrNull()?.supportedOrDashboard() ?: DashboardRoute

internal fun NavBackStack<AtxRoute>.replaceTopLevel(route: AtxRoute) {
    val supportedRoute = route.supportedOrDashboard()
    if (size != 1 || lastOrNull() != supportedRoute) {
        clear()
        add(supportedRoute)
    }
}

private fun AtxRoute.persistedStableId(): String = when (this) {
    DashboardRoute,
    ProjectsRoute,
    MapRoute,
    StudiesRoute,
    CatalogRoute,
    -> stableId

    is RfPathEditorRoute -> if (hasValidProjectId) stableId else DashboardRoute.stableId
    is UnsupportedRoute -> stableId
}

private fun parseRfPathEditorRoute(stableId: String): RfPathEditorRoute? {
    if (!stableId.startsWith(RF_PATH_EDITOR_PREFIX)) return null
    val projectId = stableId.removePrefix(RF_PATH_EDITOR_PREFIX)
    return projectId.takeIf(String::isValidRouteProjectId)?.let(::RfPathEditorRoute)
}

private fun String.isValidRouteProjectId(): Boolean =
    length in 1..MAX_RF_PATH_PROJECT_ID_LENGTH &&
        isNotBlank() &&
        this == trim() &&
        none(Char::isISOControl)

internal const val MAX_RF_PATH_PROJECT_ID_LENGTH = 128
internal const val RF_PATH_EDITOR_PREFIX = "rf-path-editor:"
private const val MAX_UNKNOWN_ROUTE_ID_LENGTH = 160
private const val MAX_PERSISTED_ROUTE_ID_LENGTH = 160
