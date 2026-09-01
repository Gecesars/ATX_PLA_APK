package com.gecesars.atxplan.data.regulatory

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McomDelimitedReaderTest {
    @Test
    fun parsesSemicolonsEscapedQuotesAndQuotedNewlinesWithoutChangingSourceText() {
        val reader = BoundedDelimitedReader(
            BufferedReader(
                StringReader(
                    "id;service;note\r\n" +
                        "1;FM;\"first; second\"\r\n" +
                        "2;GTVD;\"line one\nline \"\"two\"\"\"\r\n",
                ),
            ),
        )

        assertEquals(listOf("id", "service", "note"), reader.readRow())
        assertEquals(listOf("1", "FM", "first; second"), reader.readRow())
        assertEquals(listOf("2", "GTVD", "line one\nline \"two\""), reader.readRow())
        assertNull(reader.readRow())
    }

    @Test(expected = IOException::class)
    fun rejectsTextAfterAClosingQuote() {
        BoundedDelimitedReader(BufferedReader(StringReader("1;\"closed\"tail\n"))).readRow()
    }

    @Test(expected = IOException::class)
    fun rejectsAnUnterminatedQuotedField() {
        BoundedDelimitedReader(BufferedReader(StringReader("1;\"unterminated"))).readRow()
    }
}
