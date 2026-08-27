package com.gecesars.atxplan.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IbgeDatasetModelsTest {
    @Test
    fun `municipality search normalization is accent insensitive and whitespace bounded`() {
        assertEquals("sao jose dos campos", normalizeIbgeMunicipalitySearch("  São   José dos Campos  "))
        assertEquals("3550308", normalizeIbgeMunicipalitySearch("3550308"))
    }

    @Test
    fun `municipality search rejects an oversized untrusted query`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeIbgeMunicipalitySearch("x".repeat(MAX_MUNICIPALITY_QUERY_LENGTH + 1))
        }
    }

    @Test
    fun `municipality summary preserves zero population as a real value`() {
        val summary = municipality(
            populationTotal = 0L,
            urbanPopulation = 0L,
            ruralPopulation = 0L,
        )

        assertEquals(0L, summary.populationTotal)
        assertNull(summary.urbanPopulationFraction)
    }

    @Test
    fun `municipality summary rejects inconsistent source totals`() {
        assertThrows(IllegalArgumentException::class.java) {
            municipality(
                populationTotal = 100L,
                urbanPopulation = 60L,
                ruralPopulation = 30L,
            )
        }
    }

    private fun municipality(
        populationTotal: Long,
        urbanPopulation: Long,
        ruralPopulation: Long,
    ) = IbgeMunicipalitySummary(
        code = "3550308",
        stateCode = "35",
        stateAbbreviation = "SP",
        stateName = "São Paulo",
        name = "São Paulo",
        sectorCount = 2,
        urbanSectorCount = 1,
        ruralSectorCount = 1,
        unspecifiedSectorCount = 0,
        missingPopulationSectorCount = 0,
        populationTotal = populationTotal,
        urbanPopulation = urbanPopulation,
        ruralPopulation = ruralPopulation,
        unspecifiedPopulation = 0L,
        areaTotalKm2 = 10.0,
        urbanAreaKm2 = 4.0,
        ruralAreaKm2 = 6.0,
        unspecifiedAreaKm2 = 0.0,
        west = -47.0,
        south = -24.0,
        east = -46.0,
        north = -23.0,
    )
}
