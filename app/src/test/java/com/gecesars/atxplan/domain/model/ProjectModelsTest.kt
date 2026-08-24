package com.gecesars.atxplan.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun `project factory trims user data and creates stable timestamps`() {
        val project = ProjectFactory.create(
            name = "  Mountain Link  ",
            customer = "  Carrier A  ",
            nowEpochMillis = 1234L,
        )

        assertEquals("Mountain Link", project.name)
        assertEquals("Carrier A", project.customer)
        assertEquals(1234L, project.createdAtEpochMillis)
        assertEquals(1234L, project.updatedAtEpochMillis)
        assertFalse(project.isDemonstration)
    }

    @Test
    fun `project catalog falls back to first project when selection is stale`() {
        val project = ProjectFactory.create("Project A", "", nowEpochMillis = 1L)
        val catalog = ProjectCatalog(
            selectedProjectId = "missing",
            projects = listOf(project),
        )

        assertEquals(project.id, catalog.selectedProject?.id)
    }

    @Test
    fun `demonstration project survives serialization round trip`() {
        val json = Json { encodeDefaults = true }
        val catalog = ProjectCatalog(
            selectedProjectId = "project-demo-sao-paulo",
            projects = listOf(ProjectFactory.demonstration(nowEpochMillis = 42L)),
        )

        val restored = json.decodeFromString<ProjectCatalog>(json.encodeToString(catalog))

        assertEquals(catalog, restored)
        assertEquals(3, restored.selectedProject?.sites?.size)
        assertTrue(restored.selectedProject?.isDemonstration == true)
    }

    @Test
    fun `invalid coordinates and duplicate project ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(91.0, 0.0) }

        val project = ProjectFactory.create("Valid Project", "", nowEpochMillis = 1L)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectCatalog(projects = listOf(project, project))
        }
    }
}
