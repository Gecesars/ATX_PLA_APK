package com.gecesars.atxplan.domain.anatel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnatelBasicPlanCatalogTest {
    @Test
    fun queryRequiresConcreteServiceAndBoundedReducingFilters() {
        expectFailure {
            AnatelBasicPlanQuery(service = AnatelBroadcastService.UNKNOWN)
        }
        expectFailure {
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                stateCode = "Sao Paulo",
            )
        }
        expectFailure {
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                text = "x",
            )
        }
        expectFailure {
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                pageSize = AnatelBasicPlanCatalogLimits.MAX_PAGE_SIZE + 1,
            )
        }
        expectFailure {
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                offset = AnatelBasicPlanCatalogLimits.MAX_PAGE_OFFSET + 1,
            )
        }

        val valid = AnatelBasicPlanQuery(
            service = AnatelBroadcastService.TELEVISION,
            stateCode = "sp",
            municipality = "3550308",
            channel = 14,
            frequencyMHz = AnatelFrequencyRangeMHz(473.0, 479.0),
            text = "example broadcaster",
            basicPlanId = "PB-1002",
            pageSize = 25,
            offset = 50,
        )

        assertEquals(AnatelBroadcastService.TELEVISION, valid.service)
        assertEquals(25, valid.pageSize)
        assertEquals(50, valid.offset)
    }

    @Test
    fun frequencyRangeRejectsNoDataAndDescendingBounds() {
        expectFailure { AnatelFrequencyRangeMHz(Double.NaN, 100.0) }
        expectFailure { AnatelFrequencyRangeMHz(100.0, 99.9) }
        expectFailure { AnatelFrequencyRangeMHz(0.0, 100.0) }

        val exact = AnatelFrequencyRangeMHz(99.55, 99.55)
        assertEquals(99.55, exact.minimum, 0.0)
        assertEquals(99.55, exact.maximum, 0.0)
    }

    @Test
    fun noDataPageRemainsExplicitAndCannotCarryRecords() {
        val status = AnatelBasicPlanCatalogStatus.noData(
            AnatelBasicPlanNoDataReason.NOT_ACQUIRED,
        )
        val page = AnatelBasicPlanQueryPage(
            status = status,
            records = emptyList(),
            offset = 0,
            pageSize = 50,
            hasMore = false,
        )

        assertEquals(AnatelBasicPlanCatalogAvailability.NO_DATA, page.status.availability)
        assertEquals(AnatelBasicPlanNoDataReason.NOT_ACQUIRED, page.status.noDataReason)
        assertNull(page.status.snapshot)
        assertTrue(page.records.isEmpty())
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected an IllegalArgumentException.")
    }
}
