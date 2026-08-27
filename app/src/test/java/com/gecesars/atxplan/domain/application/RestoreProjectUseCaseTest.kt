package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreProjectUseCaseTest {
    private val useCase = RestoreProjectUseCase()

    @Test
    fun `restore inserts at the original index selects and preserves the aggregate`() {
        val first = project("project-first", "First", 1L)
        val restored = project("project-restored", "Restored", 2L)
        val last = project("project-last", "Last", 3L)
        val expectedArchive = ArchivedProject(
            project = restored,
            archivedAtEpochMillis = 10L,
            originalProjectIndex = 1,
        )
        val peerArchive = ArchivedProject(
            project = project("project-peer", "Peer Archive", 4L),
            archivedAtEpochMillis = 11L,
            originalProjectIndex = 0,
        )
        val catalog = ProjectCatalog(
            selectedProjectId = first.id,
            projects = listOf(first, last),
            archivedProjects = listOf(peerArchive, expectedArchive),
        )

        val result = useCase(catalog, RestoreProjectCommand(expectedArchive))

        assertEquals(RestoreProjectStatus.RESTORED, result.status)
        assertEquals(listOf(first, restored, last), result.catalog.projects)
        assertSame(first, result.catalog.projects[0])
        assertSame(restored, result.catalog.projects[1])
        assertSame(last, result.catalog.projects[2])
        assertSame(peerArchive, result.catalog.archivedProjects.single())
        assertEquals(restored.id, result.catalog.selectedProjectId)
        assertSame(restored, result.catalog.selectedProject)
        assertSame(restored.networks, result.catalog.selectedProject?.networks)
        assertSame(restored.sites, result.catalog.selectedProject?.sites)
        assertSame(restored.studies, result.catalog.selectedProject?.studies)
        assertSame(restored.receivers, result.catalog.selectedProject?.receivers)
    }

    @Test
    fun `restore clamps an obsolete original index to the end of latest active order`() {
        val first = project("project-first", "First", 1L)
        val restored = project("project-restored", "Restored", 2L)
        val expectedArchive = ArchivedProject(
            project = restored,
            archivedAtEpochMillis = 10L,
            originalProjectIndex = 100,
        )
        val catalog = ProjectCatalog(
            selectedProjectId = first.id,
            projects = listOf(first),
            archivedProjects = listOf(expectedArchive),
        )

        val result = useCase(catalog, RestoreProjectCommand(expectedArchive))

        assertEquals(listOf(first, restored), result.catalog.projects)
        assertSame(restored, result.catalog.projects.last())
        assertEquals(restored.id, result.catalog.selectedProjectId)
    }

    @Test
    fun `changed archive metadata rejects a stale restore with the same catalog instance`() {
        val project = project("project-restored", "Restored", 1L)
        val reviewed = ArchivedProject(project, archivedAtEpochMillis = 10L, originalProjectIndex = 0)
        val latest = reviewed.copy(archivedAtEpochMillis = 11L)
        val catalog = ProjectCatalog(archivedProjects = listOf(latest))

        val result = useCase(catalog, RestoreProjectCommand(reviewed))

        assertEquals(RestoreProjectStatus.STALE_ARCHIVE, result.status)
        assertSame(catalog, result.catalog)
        assertSame(latest, result.currentArchivedProject)
        assertFalse(result.didRestore)
    }

    @Test
    fun `changed archived aggregate rejects a stale restore`() {
        val project = project("project-restored", "Restored", 1L)
        val reviewed = ArchivedProject(project, archivedAtEpochMillis = 10L, originalProjectIndex = 0)
        val latest = reviewed.copy(project = project.copy(notes = "Changed while archived"))
        val catalog = ProjectCatalog(archivedProjects = listOf(latest))

        val result = useCase(catalog, RestoreProjectCommand(reviewed))

        assertEquals(RestoreProjectStatus.STALE_ARCHIVE, result.status)
        assertSame(catalog, result.catalog)
        assertSame(latest, result.currentArchivedProject)
    }

    @Test
    fun `repeated restore reports already active and returns the same catalog instance`() {
        val project = project("project-restored", "Restored", 1L)
        val archive = ArchivedProject(project, archivedAtEpochMillis = 10L, originalProjectIndex = 0)
        val first = useCase(
            ProjectCatalog(archivedProjects = listOf(archive)),
            RestoreProjectCommand(archive),
        )

        val repeated = useCase(first.catalog, RestoreProjectCommand(archive))

        assertEquals(RestoreProjectStatus.ALREADY_ACTIVE, repeated.status)
        assertSame(first.catalog, repeated.catalog)
        assertSame(project, repeated.currentActiveProject)
        assertFalse(repeated.didRestore)
    }

    @Test
    fun `missing restore target is a typed no-op with the same catalog instance`() {
        val active = project("project-active", "Active", 1L)
        val missing = ArchivedProject(
            project("project-missing", "Missing", 2L),
            archivedAtEpochMillis = 10L,
            originalProjectIndex = 0,
        )
        val catalog = ProjectCatalog(selectedProjectId = active.id, projects = listOf(active))

        val result = useCase(catalog, RestoreProjectCommand(missing))

        assertEquals(RestoreProjectStatus.NOT_FOUND, result.status)
        assertSame(catalog, result.catalog)
        assertTrue(result.currentArchivedProject == null)
        assertTrue(result.currentActiveProject == null)
    }

    @Test
    fun `archive then restore recreates original active and archive ordering structurally`() {
        val first = project("project-first", "First", 1L)
        val target = project("project-target", "Target", 2L)
        val last = project("project-last", "Last", 3L)
        val peerArchive = ArchivedProject(
            project("project-peer", "Peer Archive", 4L),
            archivedAtEpochMillis = 5L,
            originalProjectIndex = 0,
        )
        val original = ProjectCatalog(
            selectedProjectId = target.id,
            projects = listOf(first, target, last),
            archivedProjects = listOf(peerArchive),
        )
        val archived = ArchiveProjectUseCase(EpochMillisClock { 10L })(
            original,
            ArchiveProjectCommand(target),
        )

        val restored = useCase(
            archived.catalog,
            RestoreProjectCommand(checkNotNull(archived.archivedProject)),
        )

        assertEquals(RestoreProjectStatus.RESTORED, restored.status)
        assertEquals(original, restored.catalog)
        assertSame(first, restored.catalog.projects[0])
        assertSame(target, restored.catalog.projects[1])
        assertSame(last, restored.catalog.projects[2])
        assertSame(peerArchive, restored.catalog.archivedProjects.single())
    }

    private fun project(id: String, name: String, timestamp: Long): PlannerProject =
        ProjectFactory.demonstration(nowEpochMillis = timestamp).copy(
            id = id,
            name = name,
            isDemonstration = false,
        )
}
