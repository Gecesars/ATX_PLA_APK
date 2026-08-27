package com.gecesars.atxplan.ui.screens

import com.gecesars.atxplan.domain.model.ArchivedProject
import com.gecesars.atxplan.domain.model.ProjectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsScreenTest {
    @Test
    fun `delete confirmation requires the exact bounded keyword`() {
        assertTrue(projectDeleteConfirmationMatches("DELETE"))
        assertFalse(projectDeleteConfirmationMatches("delete"))
        assertFalse(projectDeleteConfirmationMatches(" DELETE"))
    }

    @Test
    fun `imported project names are bounded before display`() {
        val displayedName = boundedProjectNameForDisplay("A".repeat(10_000))

        assertEquals(160, displayedName.length)
        assertTrue(displayedName.endsWith("…"))
    }

    @Test
    fun `delete impact summary reports every project scoped collection`() {
        val project = ProjectFactory.demonstration(nowEpochMillis = 1L)

        assertEquals(
            "Deleting this project removes 1 network, 3 sites, 3 sectors, " +
                "0 receivers, and 2 study summaries from local storage.",
            projectDeletionImpactSummary(project),
        )
    }

    @Test
    fun `archive impact reports every retained collection without deletion language`() {
        val project = ProjectFactory.demonstration(nowEpochMillis = 1L)

        val summary = projectArchiveImpactSummary(project)

        assertEquals(
            "Archiving this project retains 1 network, 3 sites, 3 sectors, " +
                "0 receivers, and 2 study summaries in the local catalog.",
            summary,
        )
        assertFalse(summary.contains("removes", ignoreCase = true))
        assertFalse(summary.contains("deletes", ignoreCase = true))
    }

    @Test
    fun `archive disclosure describes its exact local recovery boundary`() {
        listOf(false, true).forEach { useShortLayout ->
            val disclosure = projectArchiveDisclosure(useShortLayout).lowercase()

            assertTrue(disclosure.contains("reversible"))
            assertTrue(disclosure.contains("local catalog"))
            assertTrue(disclosure.contains("backup"))
            assertTrue(disclosure.contains("export"))
            assertTrue(disclosure.contains("synchronization"))
            assertTrue(disclosure.contains("permanently deleted"))
        }
    }

    @Test
    fun `archived section title exposes its count compactly`() {
        assertEquals("Archived Projects (0)", archivedProjectsSectionTitle(0))
        assertEquals("Archived Projects (12)", archivedProjectsSectionTitle(12))
    }

    @Test
    fun `restore accessibility description bounds an imported project name`() {
        val importedName = "R".repeat(10_000)
        val archivedProject = ArchivedProject(
            project = ProjectFactory.demonstration(nowEpochMillis = 1L).copy(name = importedName),
            archivedAtEpochMillis = 2L,
            originalProjectIndex = 0,
        )

        val description = archivedProjectRestoreDescription(archivedProject)

        assertTrue(description.startsWith("Restore archived project "))
        assertTrue(description.length <= "Restore archived project ".length + 160)
        assertFalse(description.contains(importedName))
    }

    @Test
    fun `project saved state key has a fixed bound for an untrusted imported id`() {
        val importedId = "x".repeat(100_000)
        val key = projectSavedStateKey(importedId)

        assertEquals(64, key.length)
        assertTrue(key.all { character -> character.isDigit() || character in 'a'..'f' })
        assertEquals(key, projectSavedStateKey(importedId))
        assertFalse(key.contains(importedId.take(32)))
    }

    @Test
    fun `reviewed fingerprint changes with the complete project snapshot`() {
        val reviewed = ProjectFactory.demonstration(nowEpochMillis = 1L)
        val changed = reviewed.copy(notes = "Concurrent change")

        assertNotEquals(
            projectSavedStateFingerprint(reviewed),
            projectSavedStateFingerprint(changed),
        )
    }

    @Test
    fun `duplicate name suggestion is length safe for a maximum-length source`() {
        val suggestion = suggestedDuplicateProjectName(
            sourceName = "A".repeat(80),
            existingNames = emptyList(),
        )

        assertEquals(80, suggestion.length)
        assertTrue(suggestion.endsWith(" Copy"))
    }

    @Test
    fun `duplicate name suggestion advances past normalized case-insensitive collisions`() {
        val suggestion = suggestedDuplicateProjectName(
            sourceName = "  Ridge Link  ",
            existingNames = listOf(
                "Ridge Link",
                "Ridge Link Copy",
                " ridge link copy 2 ",
            ),
        )

        assertEquals("Ridge Link Copy 3", suggestion)
    }

    @Test
    fun `duplicate name suggestion gives an imported blank stem an English fallback`() {
        assertEquals(
            "Project Copy",
            suggestedDuplicateProjectName(
                sourceName = "   ",
                existingNames = emptyList(),
            ),
        )
    }
}
