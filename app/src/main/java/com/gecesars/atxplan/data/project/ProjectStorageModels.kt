package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.PROJECT_CATALOG_SCHEMA_VERSION
import com.gecesars.atxplan.domain.model.PlannerProject
import kotlinx.serialization.Serializable

internal const val PROJECT_STORE_FORMAT = "atx-project-index"
internal const val PROJECT_STORE_SCHEMA_VERSION = 1
internal const val PROJECT_DOCUMENT_SCHEMA_VERSION = 1
internal const val MIN_INDEXED_PROJECT_SCHEMA_VERSION = 4
internal const val MAX_PROJECT_DOCUMENT_BYTES: Int = 8 * 1024 * 1024

@Serializable
internal data class ProjectCatalogIndex(
    val format: String = PROJECT_STORE_FORMAT,
    val storeSchemaVersion: Int = PROJECT_STORE_SCHEMA_VERSION,
    val projectSchemaVersion: Int = PROJECT_CATALOG_SCHEMA_VERSION,
    val selectedProjectId: String? = null,
    val projects: List<ProjectDocumentReference> = emptyList(),
    val archivedProjects: List<ArchivedProjectDocumentReference> = emptyList(),
) {
    init {
        require(format == PROJECT_STORE_FORMAT) { "The project index format is not supported." }
        require(storeSchemaVersion == PROJECT_STORE_SCHEMA_VERSION) {
            "The project index schema is not supported."
        }
        require(projectSchemaVersion in MIN_INDEXED_PROJECT_SCHEMA_VERSION..PROJECT_CATALOG_SCHEMA_VERSION) {
            "The project document schema is not supported."
        }
        val activeIds = projects.map(ProjectDocumentReference::projectId)
        val archivedIds = archivedProjects.map { it.project.projectId }
        val allIds = activeIds + archivedIds
        require(allIds.distinct().size == allIds.size) {
            "Active and archived project IDs must be unique in the project index."
        }
        // Preserve the schema-3 selection contract: a stale or archived selected ID is retained,
        // while ProjectCatalog.selectedProject falls back to the first active project. Tightening
        // this invariant here would make an otherwise valid legacy catalog impossible to migrate.
    }
}

@Serializable
internal data class ProjectDocumentReference(
    val projectId: String,
    val sha256: String,
    val byteLength: Long,
) {
    init {
        require(projectId.isNotBlank()) { "A project document reference requires a project ID." }
        require(STORAGE_SHA256_PATTERN.matches(sha256)) {
            "A project document reference requires a lowercase SHA-256 digest."
        }
        require(byteLength in 1..MAX_PROJECT_DOCUMENT_BYTES.toLong()) {
            "A project document reference has an invalid byte length."
        }
    }
}

@Serializable
internal data class ArchivedProjectDocumentReference(
    val project: ProjectDocumentReference,
    val archivedAtEpochMillis: Long,
    val originalProjectIndex: Int,
) {
    init {
        require(archivedAtEpochMillis >= 0L) {
            "An archived project index timestamp cannot be negative."
        }
        require(originalProjectIndex >= 0) {
            "An archived project index position cannot be negative."
        }
    }
}

@Serializable
internal data class ProjectDocument(
    val documentSchemaVersion: Int = PROJECT_DOCUMENT_SCHEMA_VERSION,
    val projectSchemaVersion: Int = PROJECT_CATALOG_SCHEMA_VERSION,
    val project: PlannerProject,
) {
    init {
        require(documentSchemaVersion == PROJECT_DOCUMENT_SCHEMA_VERSION) {
            "The project document format is not supported."
        }
        require(projectSchemaVersion in MIN_INDEXED_PROJECT_SCHEMA_VERSION..PROJECT_CATALOG_SCHEMA_VERSION) {
            "The project document schema is not supported."
        }
    }
}

internal val STORAGE_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
