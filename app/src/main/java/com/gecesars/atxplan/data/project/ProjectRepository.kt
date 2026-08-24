package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ProjectCatalog

interface ProjectRepository {
    suspend fun loadCatalog(): ProjectCatalog

    /**
     * Applies [transform] to the latest durable catalog and atomically persists the result.
     * Implementations must serialize the complete read-transform-write operation.
     */
    suspend fun updateCatalog(transform: (ProjectCatalog) -> ProjectCatalog): ProjectCatalog
}

class ProjectStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
