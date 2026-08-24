package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class AutoConnectDelayTest {

    @Test
    fun autoConnectDelaySecondsReadsDefaultZeroWhenNotSet() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.getInt(eq("auto-connect-delay-seconds"), eq(0))).thenReturn(0)

        val settings = Settings(mockContext)
        assertEquals(0, settings.autoConnectDelaySeconds)
    }

    @Test
    fun autoConnectDelaySecondsReadsAndWritesCorrectly() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.getInt(eq("auto-connect-delay-seconds"), eq(0))).thenReturn(5)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)

        val settings = Settings(mockContext)
        assertEquals(5, settings.autoConnectDelaySeconds)

        settings.autoConnectDelaySeconds = 10
        verify(mockEditor).putInt("auto-connect-delay-seconds", 10)
        verify(mockEditor).apply()
    }
}
