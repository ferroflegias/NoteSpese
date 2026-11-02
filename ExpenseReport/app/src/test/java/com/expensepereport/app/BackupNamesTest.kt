package com.expensepereport.app

import com.expensepereport.app.data.DropboxService
import com.expensepereport.app.util.sanitizeDeviceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupNamesTest {

    @Test
    fun deviceNameIsSafeForFileNames() {
        assertEquals("Pixel-8-Pro", sanitizeDeviceName("Pixel 8 Pro"))
        assertEquals("iPhone-di-Mario", sanitizeDeviceName("  iPhone di Mario! "))
        assertEquals("Dispositivo", sanitizeDeviceName("   "))
        assertEquals(30, sanitizeDeviceName("a".repeat(50)).length)
    }

    @Test
    fun backupNameContainsTimestampAndDevice() {
        val name = DropboxService.backupFileName("20260131_142500", "Pixel 8")
        assertEquals("expense_report_backup_20260131_142500_Pixel-8.db", name)
        assertEquals("Pixel-8", DropboxService.deviceFromBackupName(name))
    }

    @Test
    fun olderBackupsHaveNoDevice() {
        assertNull(DropboxService.deviceFromBackupName("expense_report_backup_20260131_142500.db"))
    }
}
