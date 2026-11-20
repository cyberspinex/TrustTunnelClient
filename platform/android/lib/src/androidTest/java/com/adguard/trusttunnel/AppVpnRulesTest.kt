package com.adguard.trusttunnel

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppVpnRulesTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withStore(test: (File, ContextWrapper) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory(target.cacheDir.toPath(), "policy").toFile()
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = dir
        }
        try { test(dir, context) } finally { dir.deleteRecursively() }
    }

    @Test
    fun firstAndRepeatedSavesPreserveSettingsAndRevision() = withStore { dir, context ->
        val first = AppVpnStore.save(context, AppVpnRules(true, false, setOf("com.example.alpha")), "")
        assertEquals(first, AppVpnStore.read(context))
        val second = AppVpnStore.save(context, AppVpnRules(true, true, setOf("com.example.beta")), first.revision)
        assertEquals(second, AppVpnStore.read(context))
        assertFalse(first.revision == second.revision)
        assertFalse(File(dir, "app-vpn.json.previous").exists())
        assertTrue(File(dir, "app-vpn.initialized").exists())
    }

    @Test
    fun interruptedReplacementKeepsThePreviousRules() = withStore { dir, context ->
        val original = AppVpnStore.save(context, AppVpnRules(true, false, setOf("com.example.alpha")), "")
        val bytes = File(dir, "app-vpn.json").readBytes()
        for (step in listOf("temp", "backup", "renamed")) {
            assertThrows(IllegalStateException::class.java) {
                AppVpnStore.writeAtomic(dir, "app-vpn.json", "invalid") {
                    if (it == step) throw IllegalStateException(step)
                }
            }
            assertTrue(bytes.contentEquals(File(dir, "app-vpn.json").readBytes()))
            assertEquals(original, AppVpnStore.read(context))
        }
    }

    @Test
    fun interruptedFirstWriteAndMissingTargetRecoverSafely() = withStore { dir, context ->
        assertThrows(IllegalStateException::class.java) {
            AppVpnStore.writeAtomic(dir, "app-vpn.json", "incomplete") {
                if (it == "renamed") throw IllegalStateException()
            }
        }
        assertFalse(File(dir, "app-vpn.json").exists())
        val first = AppVpnStore.save(context, AppVpnRules(), "")
        val file = File(dir, "app-vpn.json")
        file.copyTo(File(dir, "app-vpn.json.previous"))
        file.delete()
        assertEquals(first, AppVpnStore.read(context))
    }

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
        val context = object : ContextWrapper(target) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == android.Manifest.permission.QUERY_ALL_PACKAGES) android.content.pm.PackageManager.PERMISSION_GRANTED
                else super.checkSelfPermission(permission)
        }
        assertEquals("empty", assertThrows(AppVpnException::class.java) { AppliedAppVpnRules.resolve(context, include) }.reason)
        assertEquals(emptyMap<String, Int>(), AppliedAppVpnRules.resolve(context, include.copy(bypass = true)).installed)
    }
}
