package com.adguard.trusttunnel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.VpnService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** The selection belongs to this Android user and is independent of the server configuration. */
data class AppVpnRules(
    val enabled: Boolean = false,
    val bypass: Boolean = false,
    val packages: Set<String> = emptySet(),
    val revision: String = ""
) {
    fun sameSelection(other: AppVpnRules): Boolean =
        enabled == other.enabled && (!enabled || (bypass == other.bypass && packages == other.packages))
}

class AppVpnException(val reason: String) : Exception(reason)

/** Both the Flutter plugin and the VPN service run in the application's default process. */
object AppVpnStore {
    private const val FILE = "app-vpn.json"
    private const val MARKER = "app-vpn.initialized"

    // File.exists() also returns false on access and I/O errors.
    private fun exists(file: File): Boolean = try {
        Os.stat(file.path)
        true
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.ENOENT) throw e
        false
    }

    @Synchronized
    fun read(context: Context): AppVpnRules {
        try {
            val dir = context.noBackupFilesDir
            val file = File(dir, FILE)
            val marker = File(dir, MARKER)
            if (!exists(file)) {
                if (exists(marker) || !OsConstants.S_ISDIR(Os.stat(dir.path).st_mode)) throw AppVpnException("storage")
                return AppVpnRules() // First install or upgrade from a version without App VPN.
            }
            val json = JSONObject(file.readText())
            if (json.get("version") != 1) throw AppVpnException("version")
            val packages = json.getJSONArray("packages")
            val names = (0 until packages.length()).map { packages.get(it) as String }.toSet()
            if (names.any { !validName(it) }) throw AppVpnException("storage")
            val bypass = when (json.getString("mode")) {
                "include" -> false
                "exclude" -> true
                else -> throw AppVpnException("storage")
            }
            val revision = json.get("revision") as String
            if (revision.isBlank()) throw AppVpnException("storage")
            return AppVpnRules(json.get("enabled") as Boolean, bypass, names, revision)
        } catch (e: AppVpnException) {
            throw e
        } catch (_: Exception) {
            throw AppVpnException("storage")
        }
    }

    private val packageName = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*""")
    private fun validName(name: String) = packageName.matches(name)

    /** expectedRevision prevents two attached engines from overwriting each other's changes. */
    @Synchronized
    fun save(context: Context, rules: AppVpnRules, expectedRevision: String?, repair: Boolean = false): AppVpnRules {
        val previous = try {
            read(context)
        } catch (e: AppVpnException) {
            if (!repair || rules != AppVpnRules()) throw e
            null
        }
        if (previous?.revision != expectedRevision) throw AppVpnException("changed")
        if (rules.packages.any { !validName(it) }) throw AppVpnException("storage")
        val saved = rules.copy(packages = rules.packages.toSortedSet(), revision = UUID.randomUUID().toString())
        val data = JSONObject().put("version", 1).put("enabled", saved.enabled)
            .put("mode", if (saved.bypass) "exclude" else "include")
            .put("packages", JSONArray(saved.packages)).put("revision", saved.revision).toString()
        try {
            val dir = context.noBackupFilesDir
            if (!exists(File(dir, MARKER))) {
                // Keep a valid disabled record even if the first user edit fails halfway through.
                if (previous?.revision == "") {
                    val initial = JSONObject(data).put("enabled", false).put("mode", "include")
                        .put("packages", JSONArray())
                    writeAtomic(dir, FILE, initial.toString())
                }
                writeAtomic(dir, MARKER, "1")
            }
            writeAtomic(dir, FILE, data)
        } catch (_: Exception) {
            throw AppVpnException("write")
        }
        return saved
    }

    private fun writeAtomic(dir: File, name: String, value: String) {
        val target = File(dir, name)
        val previous = File(dir, "$name.previous")
        val directory = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            val temp = File.createTempFile("app-vpn-", ".tmp", dir)
            try {
                FileOutputStream(temp).use {
                    it.write(value.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                if (exists(previous)) Files.delete(previous.toPath())
                val hadPrevious = exists(target)
                if (hadPrevious) Os.link(target.path, previous.path)
                Os.rename(temp.path, target.path)
                try {
                    Os.fsync(directory)
                } catch (e: ErrnoException) {
                    if (hadPrevious) Os.rename(previous.path, target.path) else Files.delete(target.toPath())
                    Os.fsync(directory)
                    throw e
                }
            } finally {
                temp.delete()
                previous.delete()
            }
        } finally {
            Os.close(directory)
        }
    }
}

data class AppliedAppVpnRules(val rules: AppVpnRules, val installed: Map<String, Int>) {
    companion object {
        fun resolve(context: Context, rules: AppVpnRules): AppliedAppVpnRules {
            if (!rules.enabled) return AppliedAppVpnRules(rules, emptyMap())
            if (Build.VERSION.SDK_INT >= 30 && rules.packages.isNotEmpty() &&
                context.checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
                throw AppVpnException("package")
            }
            val installed = mutableMapOf<String, Int>()
            for (name in rules.packages) {
                val uid = try {
                    context.packageManager.getPackageUid(name, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    continue // Keep uninstalled names in the saved rules for reinstallation.
                } catch (_: RuntimeException) {
                    throw AppVpnException("package")
                }
                if (uid == context.applicationInfo.uid) throw AppVpnException("self")
                installed[name] = uid
            }
            // Android routes UIDs, not individual packages within a shared UID.
            for (uid in installed.values.toSet()) {
                val members = try { context.packageManager.getPackagesForUid(uid) } catch (_: RuntimeException) { null }
                if (members.isNullOrEmpty() || members.any { it !in rules.packages }) throw AppVpnException("group")
            }
            if (!rules.bypass && installed.isEmpty()) throw AppVpnException("empty")
            return AppliedAppVpnRules(rules, installed)
        }
    }

    fun applyTo(builder: VpnService.Builder, context: Context) {
        try {
            if (!rules.enabled || rules.bypass) builder.addDisallowedApplication(context.packageName)
            for ((name, uid) in installed) {
                // A removal or UID change between resolution and establish invalidates this builder.
                if (context.packageManager.getPackageUid(name, 0) != uid) throw AppVpnException("package")
                if (rules.bypass) builder.addDisallowedApplication(name) else builder.addAllowedApplication(name)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw AppVpnException("package")
        } catch (_: RuntimeException) {
            throw AppVpnException("package")
        }
    }
}
