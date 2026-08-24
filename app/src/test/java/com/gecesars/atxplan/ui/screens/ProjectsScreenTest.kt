package com.gecesars.atxplan.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsScreenTest {
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
