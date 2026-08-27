package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveProjectUseCaseTest {
    @Test
    fun `archiving the selected first project selects its next neighbor`() {
        val first = project("project-first", "First", 1L)
        val second = project("project-second", "Second", 2L)
        val last = project("project-last", "Last", 3L)
        val catalog = ProjectCatalog(
            selectedProjectId = first.id,
            projects = listOf(first, second, last),
        )

        val result = useCase(now = 10L)(catalog, ArchiveProjectCommand(first))

        assertEquals(ArchiveProjectStatus.ARCHIVED, result.status)
        assertEquals(listOf(second, last), result.catalog.projects)
        assertEquals(second.id, result.catalog.selectedProjectId)
        assertSame(second, result.catalog.projects[0])
        assertSame(last, result.catalog.projects[1])
        assertEquals(first, result.archivedProject?.project)
        assertSame(first, result.archivedProject?.project)
        assertEquals(10L, result.archivedProject?.archivedAtEpochMillis)
        assertEquals(0, result.archivedProject?.originalProjectIndex)
    }

    @Test
    fun `archiving the selected middle or last project follows next then previous policy`() {
        val first = project("project-first", "First", 1L)
        val middle = project("project-middle", "Middle", 2L)
        val last = project("project-last", "Last", 3L)
        val projects = listOf(first, middle, last)

        val middleResult = useCase(10L)(
            ProjectCatalog(selectedProjectId = middle.id, projects = projects),
            ArchiveProjectCommand(middle),
        )
        val lastResult = useCase(11L)(
            ProjectCatalog(selectedProjectId = last.id, projects = projects),
            ArchiveProjectCommand(last),
        )

        assertEquals(last.id, middleResult.catalog.selectedProjectId)
        assertEquals(listOf(first, last), middleResult.catalog.projects)
        assertEquals(1, middleResult.archivedProject?.originalProjectIndex)
        assertEquals(middle.id, lastResult.catalog.selectedProjectId)
        assertEquals(listOf(first, middle), lastResult.catalog.projects)
        assertEquals(2, lastResult.archivedProject?.originalProjectIndex)
    }

    @Test
    fun `archiving the only active project leaves no active selection`() {
        val only = project("project-only", "Only", 1L)

        val result = useCase(10L)(
            ProjectCatalog(selectedProjectId = only.id, projects = listOf(only)),
            ArchiveProjectCommand(only),
        )

        assertTrue(result.catalog.projects.isEmpty())
        assertNull(result.catalog.selectedProjectId)
        assertNull(result.catalog.selectedProject)
        assertEquals(listOf(only), result.catalog.archivedProjects.map { it.project })
    }

    @Test
    fun `archiving an unselected project preserves a valid selection and appends to archive`() {
        val selected = project("project-selected", "Selected", 1L)
        val target = project("project-target", "Target", 2L)
        val peerArchive = ArchivedProject(
            project = project("project-archived", "Earlier Archive", 3L),
            archivedAtEpochMillis = 4L,
            originalProjectIndex = 0,
        )
        val catalog = ProjectCatalog(
            selectedProjectId = selected.id,
            projects = listOf(selected, target),
            archivedProjects = listOf(peerArchive),
        )

        val result = useCase(10L)(catalog, ArchiveProjectCommand(target))

        assertEquals(selected.id, result.catalog.selectedProjectId)
        assertSame(selected, result.catalog.projects.single())
        assertSame(peerArchive, result.catalog.archivedProjects.first())
        assertSame(target, result.catalog.archivedProjects.last().project)
        assertEquals(listOf(peerArchive, result.archivedProject), result.catalog.archivedProjects)
        assertEquals(catalog.projects, listOf(selected, target))
        assertEquals(listOf(peerArchive), catalog.archivedProjects)
    }

    @Test
    fun `archive normalizes an invalid selection to the first remaining active project`() {
        val first = project("project-first", "First", 1L)
        val target = project("project-target", "Target", 2L)
        val catalog = ProjectCatalog(
            selectedProjectId = "missing-selection",
            projects = listOf(first, target),
        )

        val result = useCase(10L)(catalog, ArchiveProjectCommand(target))

        assertEquals(first.id, result.catalog.selectedProjectId)
        assertSame(first, result.catalog.selectedProject)
    }

    @Test
    fun `complete aggregate changes reject a stale archive without reading the clock`() {
        val reviewed = project("project-target", "Target", 1L)
        val latest = reviewed.copy(
            sites = reviewed.sites.map { site ->
                site.copy(
                    sectors = site.sectors.map { sector -> sector.copy(active = !sector.active) },
                )
            },
        )
        val catalog = ProjectCatalog(selectedProjectId = latest.id, projects = listOf(latest))
        var clockCalls = 0
        val useCase = ArchiveProjectUseCase(
            EpochMillisClock {
                clockCalls += 1
                10L
            },
        )

        val result = useCase(catalog, ArchiveProjectCommand(reviewed))

        assertEquals(ArchiveProjectStatus.STALE_PROJECT, result.status)
        assertSame(catalog, result.catalog)
        assertSame(latest, result.currentProject)
        assertNull(result.archivedProject)
        assertEquals(0, clockCalls)
    }

    @Test
    fun `repeated archive reports already archived and returns the same catalog instance`() {
        val project = project("project-target", "Target", 1L)
        val first = useCase(10L)(
            ProjectCatalog(selectedProjectId = project.id, projects = listOf(project)),
            ArchiveProjectCommand(project),
        )

        val repeated = useCase(20L)(first.catalog, ArchiveProjectCommand(project))

        assertEquals(ArchiveProjectStatus.ALREADY_ARCHIVED, repeated.status)
        assertSame(first.catalog, repeated.catalog)
        assertSame(first.archivedProject, repeated.archivedProject)
        assertFalse(repeated.didArchive)
    }

    @Test
    fun `missing archive target is a typed no-op with the same catalog instance`() {
        val active = project("project-active", "Active", 1L)
        val missing = project("project-missing", "Missing", 2L)
        val catalog = ProjectCatalog(selectedProjectId = active.id, projects = listOf(active))

        val result = useCase(10L)(catalog, ArchiveProjectCommand(missing))

        assertEquals(ArchiveProjectStatus.NOT_FOUND, result.status)
        assertSame(catalog, result.catalog)
        assertNull(result.currentProject)
        assertNull(result.archivedProject)
    }

    @Test
    fun `negative archive clock is rejected without mutating the active catalog`() {
        val project = project("project-target", "Target", 1L)
        val catalog = ProjectCatalog(selectedProjectId = project.id, projects = listOf(project))

        assertThrows(IllegalArgumentException::class.java) {
            useCase(-1L)(catalog, ArchiveProjectCommand(project))
        }

        assertEquals(listOf(project), catalog.projects)
        assertTrue(catalog.archivedProjects.isEmpty())
        assertEquals(project.id, catalog.selectedProjectId)
        assertSame(project, catalog.projects.single())
    }

    private fun useCase(now: Long) = ArchiveProjectUseCase(EpochMillisClock { now })

    private fun project(id: String, name: String, timestamp: Long): PlannerProject =
        ProjectFactory.demonstration(nowEpochMillis = timestamp).copy(
            id = id,
            name = name,
            isDemonstration = false,
        )
}
