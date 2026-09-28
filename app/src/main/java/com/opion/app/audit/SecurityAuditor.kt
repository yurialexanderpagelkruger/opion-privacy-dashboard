package com.opion.app.audit

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.opion.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Level { OK, INFO, WARN, BAD }

data class AuditItem(
    val title: String,
    val detail: String,
    val level: Level
)

object SecurityAuditor {

    private val ROOT_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/data/local/su", "/system/bin/failsafe/su",
        "/system/xbin/daemonsu", "/system/xbin/busybox",
        "/system/bin/.ext/.su", "/sbin/.magisk", "/data/adb/magisk",
        "/data/adb/ksu", "/data/adb/ap"
    )

    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "me.weishu.kernelsu"
    )

    suspend fun run(context: Context): List<AuditItem> = withContext(Dispatchers.IO) {
        buildList {
            add(checkScreenLock(context))
            add(checkBiometrics(context))
            add(checkUsbDebugging(context))
            add(checkDeveloperOptions(context))
            add(checkWirelessDebugging(context))
            add(checkRoot(context))
            add(checkEncryption(context))
            add(checkPrivateDns(context))
            add(checkAccessibility(context))
            add(checkNotificationListeners(context))
            add(checkDeviceAdmins(context))
            add(checkScreenTimeout(context))
            add(checkSelinux(context))
        }
    }

    private fun checkScreenLock(context: Context): AuditItem {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = try { km.isDeviceSecure } catch (_: Throwable) { false }
        return if (secure) {
            AuditItem(
                context.getString(R.string.audit_lock_title),
                context.getString(R.string.audit_lock_ok),
                Level.OK
            )
        } else {
            AuditItem(
                context.getString(R.string.audit_lock_title),
                context.getString(R.string.audit_lock_bad),
                Level.BAD
            )
        }
    }

    private fun checkBiometrics(context: Context): AuditItem {
        val pm = context.packageManager
        val has = try {
            pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            pm.hasSystemFeature(PackageManager.FEATURE_FACE))
        } catch (_: Throwable) { false }
        return AuditItem(
            context.getString(R.string.audit_bio_title),
            context.getString(if (has) R.string.audit_bio_ok else R.string.audit_bio_none),
            if (has) Level.OK else Level.INFO
        )
    }

    private fun checkUsbDebugging(context: Context): AuditItem {
        val value = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        } catch (_: Throwable) { 0 }
        return AuditItem(
            context.getString(R.string.audit_usb_title),
            context.getString(if (value == 1) R.string.audit_usb_bad else R.string.audit_usb_ok),
            if (value == 1) Level.BAD else Level.OK
        )
    }

    private fun checkDeveloperOptions(context: Context): AuditItem {
        val value = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            )
        } catch (_: Throwable) { 0 }
        return AuditItem(
            context.getString(R.string.audit_dev_title),
            context.getString(if (value == 1) R.string.audit_dev_warn else R.string.audit_dev_ok),
            if (value == 1) Level.WARN else Level.OK
        )
    }

    private fun checkWirelessDebugging(context: Context): AuditItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AuditItem(
                context.getString(R.string.audit_wadb_title),
                context.getString(R.string.audit_wadb_na),
                Level.INFO
            )
        }
        val value = try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
        } catch (_: Throwable) { 0 }
        return AuditItem(
            context.getString(R.string.audit_wadb_title),
            context.getString(if (value == 1) R.string.audit_wadb_bad else R.string.audit_wadb_ok),
            if (value == 1) Level.BAD else Level.OK
        )
    }

    private fun checkRoot(context: Context): AuditItem {
        val binaries = ROOT_PATHS.filter { path ->
            try { File(path).exists() } catch (_: Throwable) { false }
        }
        val packages = ROOT_PACKAGES.filter { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }
        }
        val testKeys = Build.TAGS?.contains("test-keys") == true

        val parts = mutableListOf<String>()
        if (binaries.isNotEmpty()) {
            val preview = binaries.take(3).joinToString(", ") +
                    if (binaries.size > 3)
                        context.getString(R.string.audit_root_more, binaries.size - 3)
                    else ""
            parts += context.getString(R.string.audit_root_binaries, preview)
        }
        if (packages.isNotEmpty()) {
            parts += context.getString(R.string.audit_root_packages, packages.joinToString(", "))
        }
        if (testKeys) parts += context.getString(R.string.audit_root_testkeys)

        val detail = if (parts.isEmpty()) context.getString(R.string.audit_root_clean)
        else parts.joinToString("\n")

        val level = when {
            binaries.isNotEmpty() || packages.isNotEmpty() -> Level.BAD
            testKeys -> Level.WARN
            else -> Level.OK
        }
        return AuditItem(context.getString(R.string.audit_root_title), detail, level)
    }

    private fun checkEncryption(context: Context): AuditItem {
        val status = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.storageEncryptionStatus
        } catch (_: Throwable) { -1 }
        val (res, level) = when {
            status >= 3 -> R.string.audit_enc_ok to Level.OK
            status == 1 -> R.string.audit_enc_bad to Level.BAD
            else -> R.string.audit_enc_unknown to Level.INFO
        }
        return AuditItem(context.getString(R.string.audit_enc_title), context.getString(res), level)
    }

    private fun checkPrivateDns(context: Context): AuditItem {
        val mode = try {
            Settings.Global.getString(context.contentResolver, "private_dns_mode")
        } catch (_: Throwable) { null }
        val specifier = try {
            Settings.Global.getString(context.contentResolver, "private_dns_specifier")
        } catch (_: Throwable) { null }

        val title = context.getString(R.string.audit_dns_title)
        return when (mode) {
            "hostname" -> AuditItem(
                title,
                context.getString(
                    R.string.audit_dns_hostname,
                    specifier ?: context.getString(R.string.audit_dns_unspecified)
                ),
                Level.OK
            )
            "opportunistic" -> AuditItem(
                title, context.getString(R.string.audit_dns_opportunistic), Level.WARN
            )
            "off" -> AuditItem(
                title, context.getString(R.string.audit_dns_off), Level.WARN
            )
            else -> AuditItem(
                title, context.getString(R.string.audit_dns_unknown), Level.INFO
            )
        }
    }

    private fun countServices(context: Context, key: String): Int {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (_: Throwable) { null }
        return raw?.split(':')?.count { it.isNotBlank() && it.contains('/') } ?: 0
    }

    private fun checkAccessibility(context: Context): AuditItem {
        val count = countServices(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val title = context.getString(R.string.audit_acc_title)
        return if (count > 0) {
            AuditItem(title, context.getString(R.string.audit_acc_warn, count), Level.WARN)
        } else {
            AuditItem(title, context.getString(R.string.audit_acc_ok), Level.OK)
        }
    }

    private fun checkNotificationListeners(context: Context): AuditItem {
        val count = countServices(context, "enabled_notification_listeners")
        val title = context.getString(R.string.audit_notif_title)
        return if (count > 0) {
            AuditItem(title, context.getString(R.string.audit_notif_warn, count), Level.WARN)
        } else {
            AuditItem(title, context.getString(R.string.audit_notif_ok), Level.OK)
        }
    }

    private fun checkDeviceAdmins(context: Context): AuditItem {
        val count = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins?.size ?: 0
        } catch (_: Throwable) { 0 }
        val title = context.getString(R.string.audit_admin_title)
        return if (count > 0) {
            AuditItem(title, context.getString(R.string.audit_admin_warn, count), Level.WARN)
        } else {
            AuditItem(title, context.getString(R.string.audit_admin_ok), Level.OK)
        }
    }

    private fun checkScreenTimeout(context: Context): AuditItem {
        val timeout = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)
        } catch (_: Throwable) { 0 }
        val title = context.getString(R.string.audit_timeout_title)
        return when {
            timeout <= 0 -> AuditItem(
                title, context.getString(R.string.audit_timeout_unknown), Level.INFO
            )
            timeout / 1000 <= 60 -> AuditItem(
                title, context.getString(R.string.audit_timeout_ok, timeout / 1000), Level.OK
            )
            else -> AuditItem(
                title, context.getString(R.string.audit_timeout_warn, timeout / 1000), Level.WARN
            )
        }
    }

    private fun checkSelinux(context: Context): AuditItem {
        val status = try {
            val proc = ProcessBuilder("getenforce").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            proc.waitFor()
            out
        } catch (_: Throwable) { "" }
        val title = context.getString(R.string.audit_selinux_title)
        val normalized = status.uppercase()
        return when {
            normalized.contains("ENFORCING") -> AuditItem(
                title, context.getString(R.string.audit_selinux_ok), Level.OK
            )
            normalized.contains("PERMISSIVE") -> AuditItem(
                title, context.getString(R.string.audit_selinux_bad), Level.BAD
            )
            else -> AuditItem(
                title, context.getString(R.string.audit_selinux_unknown), Level.INFO
            )
        }
    }
}
