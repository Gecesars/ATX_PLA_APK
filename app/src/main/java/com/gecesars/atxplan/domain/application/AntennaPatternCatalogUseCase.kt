package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.Sector

enum class AntennaPatternMutationStatus {
    INSTALLED,
    DUPLICATE,
    ASSIGNED,
    DELETED,
    UNCHANGED,
    NOT_FOUND,
    STALE,
    BLOCKED_REFERENCES,
    NOT_CALCULATION_READY,
}

data class AntennaPatternMutationResult(
    val catalog: ProjectCatalog,
    val status: AntennaPatternMutationStatus,
    val patternId: String? = null,
    val affectedSectorCount: Int = 0,
    val reason: String? = null,
)

data class InstallAntennaPatternCommand(
    val projectId: String,
    val pattern: AntennaPatternRecord,
    val canonicalArtifact: ProjectArtifactReference,
    val sourceArtifact: ProjectArtifactReference? = null,
)

data class AssignTransmitAntennaPatternCommand(
    val projectId: String,
    val siteId: String,
    val expectedSector: Sector,
    val patternId: String?,
)

data class DeleteAntennaPatternCommand(
    val projectId: String,
    val expectedPattern: AntennaPatternRecord,
)

/** Stale-safe project mutations for calculation-ready antenna patterns. */
class AntennaPatternCatalogUseCase(
    private val clock: EpochMillisClock = EpochMillisClock { System.currentTimeMillis() },
) {
    fun install(
        catalog: ProjectCatalog,
        command: InstallAntennaPatternCommand,
    ): AntennaPatternMutationResult {
        val projectIndex = catalog.projects.indexOfFirst { project -> project.id == command.projectId }
        if (projectIndex < 0) return notFound(catalog)
        require(command.canonicalArtifact.role == ProjectArtifactRole.ANTENNA_PATTERN) {
            "An installed antenna artifact requires the ANTENNA_PATTERN role."
        }
        require(command.pattern.dataArtifactId == command.canonicalArtifact.id) {
            "The antenna record must reference the installed canonical artifact."
        }
        require(command.sourceArtifact == null || command.sourceArtifact.role == ProjectArtifactRole.IMPORT_SOURCE) {
            "An antenna source artifact requires the IMPORT_SOURCE role."
        }
        require(command.pattern.sourceArtifactId == command.sourceArtifact?.id) {
            "The antenna record source reference must match the installed source artifact."
        }
        require(command.pattern.hasVerifiedNormalizedContentIdentity()) {
            "An installed antenna pattern requires explicitly available HRP and VRP cuts with " +
                "a verified gain-bound normalized content identity."
        }
        val project = catalog.projects[projectIndex]
        val duplicate = project.antennaPatterns.firstOrNull { pattern ->
            if (pattern.normalizedContentSha256 != command.pattern.normalizedContentSha256) {
                false
            } else {
                require(pattern.hasVerifiedNormalizedContentIdentity()) {
                    "A stored antenna pattern has a normalized content hash that does not " +
                        "match its calculation fields."
                }
                val existingArtifact = requireNotNull(
                    project.artifacts.singleOrNull { artifact ->
                        artifact.id == pattern.dataArtifactId &&
                            artifact.role == ProjectArtifactRole.ANTENNA_PATTERN
                    },
                ) {
                    "A stored antenna pattern does not reference exactly one canonical " +
                        "antenna artifact."
                }
                existingArtifact.sha256 == command.canonicalArtifact.sha256
            }
        }
        if (duplicate != null) {
            return AntennaPatternMutationResult(
                catalog = catalog,
                status = AntennaPatternMutationStatus.DUPLICATE,
                patternId = duplicate.id,
            )
        }
        require(project.antennaPatterns.none { pattern -> pattern.id == command.pattern.id }) {
            "The antenna pattern ID already exists in this project."
        }
        val installedArtifacts = listOfNotNull(command.sourceArtifact, command.canonicalArtifact)
        require(installedArtifacts.map(ProjectArtifactReference::id).distinct().size == installedArtifacts.size) {
            "Installed antenna artifacts require distinct IDs."
        }
        require(installedArtifacts.none { candidate ->
            project.artifacts.any { artifact -> artifact.id == candidate.id }
        }) {
            "The antenna artifact ID already exists in this project."
        }
        val updatedProject = project.copy(
            updatedAtEpochMillis = maxOf(clock.nowEpochMillis(), project.updatedAtEpochMillis),
            antennaPatterns = project.antennaPatterns + command.pattern,
            artifacts = project.artifacts + installedArtifacts,
        )
        return AntennaPatternMutationResult(
            catalog = replaceProject(catalog, projectIndex, updatedProject),
            status = AntennaPatternMutationStatus.INSTALLED,
            patternId = command.pattern.id,
        )
    }

    fun assignTransmitPattern(
        catalog: ProjectCatalog,
        command: AssignTransmitAntennaPatternCommand,
    ): AntennaPatternMutationResult {
        val projectIndex = catalog.projects.indexOfFirst { project -> project.id == command.projectId }
        if (projectIndex < 0) return notFound(catalog)
        val project = catalog.projects[projectIndex]
        val requestedPattern = command.patternId?.let { patternId ->
            project.antennaPatterns.firstOrNull { pattern -> pattern.id == patternId }
                ?: return notFound(catalog, patternId)
        }
        if (requestedPattern != null && !requestedPattern.hasVerifiedNormalizedContentIdentity()) {
            return AntennaPatternMutationResult(
                catalog = catalog,
                status = AntennaPatternMutationStatus.NOT_CALCULATION_READY,
                patternId = requestedPattern.id,
                reason =
                    "The antenna pattern lacks explicitly available HRP and VRP cuts or its " +
                        "gain-bound normalized content identity is invalid.",
            )
        }
        val siteIndex = project.sites.indexOfFirst { site -> site.id == command.siteId }
        if (siteIndex < 0) return notFound(catalog, command.patternId)
        val site = project.sites[siteIndex]
        val sectorIndex = site.sectors.indexOfFirst { sector -> sector.id == command.expectedSector.id }
        if (sectorIndex < 0) return notFound(catalog, command.patternId)
        val currentSector = site.sectors[sectorIndex]
        if (currentSector != command.expectedSector) {
            return AntennaPatternMutationResult(
                catalog = catalog,
                status = AntennaPatternMutationStatus.STALE,
                patternId = command.patternId,
            )
        }
        if (currentSector.transmitAntennaPatternId == command.patternId) {
            return AntennaPatternMutationResult(
                catalog = catalog,
                status = AntennaPatternMutationStatus.UNCHANGED,
                patternId = command.patternId,
            )
        }
        val sectors = site.sectors.toMutableList().apply {
            this[sectorIndex] = currentSector.copy(transmitAntennaPatternId = command.patternId)
        }
        val sites = project.sites.toMutableList().apply {
            this[siteIndex] = site.copy(sectors = sectors)
        }
        val updatedProject = project.copy(
            updatedAtEpochMillis = maxOf(clock.nowEpochMillis(), project.updatedAtEpochMillis),
            sites = sites,
        )
        return AntennaPatternMutationResult(
            catalog = replaceProject(catalog, projectIndex, updatedProject),
            status = AntennaPatternMutationStatus.ASSIGNED,
            patternId = command.patternId,
            affectedSectorCount = 1,
        )
    }

    fun delete(
        catalog: ProjectCatalog,
        command: DeleteAntennaPatternCommand,
    ): AntennaPatternMutationResult {
        val projectIndex = catalog.projects.indexOfFirst { project -> project.id == command.projectId }
        if (projectIndex < 0) return notFound(catalog)
        val project = catalog.projects[projectIndex]
        val current = project.antennaPatterns.firstOrNull { pattern ->
            pattern.id == command.expectedPattern.id
        } ?: return notFound(catalog, command.expectedPattern.id)
        if (current != command.expectedPattern) {
            return AntennaPatternMutationResult(
                catalog = catalog,
                status = AntennaPatternMutationStatus.STALE,
                patternId = current.id,
            )
        }
        val referencedCount = project.sites.sumOf { site ->
            site.sectors.count { sector ->
                sector.transmitAntennaPatternId == current.id ||
                    sector.receiveAntennaPatternId == current.id
            }
        }
        if (referencedCount > 0) {
            return AntennaPatternMutationResult(
                catalog = catalog,
                status = AntennaPatternMutationStatus.BLOCKED_REFERENCES,
                patternId = current.id,
                affectedSectorCount = referencedCount,
            )
        }
        val updatedProject = project.copy(
            updatedAtEpochMillis = maxOf(clock.nowEpochMillis(), project.updatedAtEpochMillis),
            antennaPatterns = project.antennaPatterns.filterNot { pattern -> pattern.id == current.id },
            artifacts = project.artifacts.filterNot { artifact ->
                artifact.id == current.dataArtifactId || artifact.id == current.sourceArtifactId
            },
        )
        return AntennaPatternMutationResult(
            catalog = replaceProject(catalog, projectIndex, updatedProject),
            status = AntennaPatternMutationStatus.DELETED,
            patternId = current.id,
        )
    }

    private fun replaceProject(
        catalog: ProjectCatalog,
        projectIndex: Int,
        project: PlannerProject,
    ): ProjectCatalog = catalog.copy(
        projects = catalog.projects.toMutableList().apply { this[projectIndex] = project },
    )

    private fun notFound(
        catalog: ProjectCatalog,
        patternId: String? = null,
    ) = AntennaPatternMutationResult(
        catalog = catalog,
        status = AntennaPatternMutationStatus.NOT_FOUND,
        patternId = patternId,
    )
}
