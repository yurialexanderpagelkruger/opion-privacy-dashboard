package com.opion.app.shortcuts

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.opion.app.R

object PrivacyShortcuts {

    class Target(
        val title: String,
        val intents: List<Intent>
    )

    fun open(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Throwable) {
            }
        }
        return try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun targets(context: Context): List<Target> = buildList {

        add(
            Target(
                context.getString(R.string.sc_permissions),
                buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Intent("android.settings.PRIVACY_SETTINGS"))
                    }
                    add(appDetails(context))
                    add(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_dns),
                buildList {
                    add(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                    add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_battery),
                buildList {
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                    add(appDetails(context))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_autostart),
                buildList {
                    for (component in AUTOSTART_COMPONENTS) {
                        add(Intent().setComponent(component))
                    }
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_devoptions),
                buildList {
                    add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    add(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_accessibility),
                buildList {
                    add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_notifications),
                buildList {
                    add(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                    add(appDetails(context))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_data),
                buildList {
                    add(Intent("android.settings.DATA_USAGE_SETTINGS"))
                    add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_security),
                buildList {
                    add(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )

        add(
            Target(
                context.getString(R.string.sc_location),
                buildList {
                    add(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    add(Intent(Settings.ACTION_SETTINGS))
                }
            )
        )
    }

    private fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )

    private val AUTOSTART_COMPONENTS: List<ComponentName> = listOf(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        ComponentName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        ComponentName(
            "com.samsung.android.sm",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        ComponentName(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        ),
        ComponentName(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.powersaver.PowerSaverSettings"
        ),
        ComponentName(
            "com.transsion.phonemaster",
            "com.itel.autostart.FeatureActivity"
        )
    )
}
