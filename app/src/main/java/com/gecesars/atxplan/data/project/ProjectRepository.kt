package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ProjectCatalog

interface ProjectRepository {
    suspend fun loadCatalog(): ProjectCatalog

    suspend fun saveCatalog(catalog: ProjectCatalog)
}

class ProjectStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
