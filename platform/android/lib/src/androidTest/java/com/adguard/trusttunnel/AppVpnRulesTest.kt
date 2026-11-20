package com.adguard.trusttunnel

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppVpnRulesTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingPreviouslySavedRulesCannotBecomeAnUnfilteredConnection() {
        val dir = java.nio.file.Files.createTempDirectory(target.cacheDir.toPath(), "policy").toFile()
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = dir
        }
        try {
            val initial = AppVpnStore.read(context)
            val changed = AppVpnStore.save(context, AppVpnRules(enabled = true), initial.revision)
            assertEquals(changed, AppVpnStore.read(context))
            File(dir, "app-vpn.json").delete()
            assertEquals("storage", assertThrows(AppVpnException::class.java) { AppVpnStore.read(context) }.reason)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zeroInstalledPackagesOnlyAllowsExclusionMode() {
        val include = AppVpnRules(enabled = true, packages = setOf("com.example.navqs.absent.fixture"))
        assertEquals("empty", assertThrows(AppVpnException::class.java) { AppliedAppVpnRules.resolve(target, include) }.reason)
        assertEquals(emptyMap<String, Int>(), AppliedAppVpnRules.resolve(target, include.copy(bypass = true)).installed)
    }
}
