package com.gecesars.atxplan.data.scheduler.work

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalWorkManifestInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test
    fun mergedManifestDeclaresExactForegroundAndPrivateCancellationComponents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)

        val foregroundService = packageInfo.services.orEmpty().singleOrNull { service ->
            service.name == "androidx.work.impl.foreground.SystemForegroundService"
        }
        assertNotNull(foregroundService)
        assertFalse(requireNotNull(foregroundService).exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundService.foregroundServiceType,
        )

        val cancelReceiver = packageInfo.receivers.orEmpty().singleOrNull { receiver ->
            receiver.name == RegionalJobCancelReceiver::class.java.name
        }
        assertNotNull(cancelReceiver)
        assertFalse(requireNotNull(cancelReceiver).exported)
    }
}
