package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogExporterClipboardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFileReadForClipboard() {
        val file = tempFolder.newFile("test_log.txt")
        file.writeText("Log content line 1\nLog content line 2")

        assertTrue(file.exists())
        assertEquals("Log content line 1\nLog content line 2", file.readText())
    }
}
