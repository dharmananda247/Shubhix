package com.shubhix.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Phone-brand specific help for keeping Shubhix AI alive in the background. */
object PhoneGuide {
    private val brand: String get() = (Build.MANUFACTURER ?: "").lowercase()

    fun title(): String = "Keep running on your " + (Build.MANUFACTURER ?: "phone")

    fun steps(): String = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
            "1. Open Autostart and switch Shubhix AI ON.\n2. Phone Settings > Apps > Shubhix AI > Battery saver > No restrictions.\n3. In the recent apps screen, long-press Shubhix AI and tap the lock icon."
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
            "1. Settings > Apps > Shubhix AI > Battery usage > Allow background activity.\n2. Turn ON Allow auto launch.\n3. In recent apps, pull Shubhix AI down to lock it."
        brand.contains("vivo") || brand.contains("iqoo") ->
            "1. Settings > Battery > Background power consumption > Shubhix AI > Allow.\n2. Allow it to start automatically.\n3. Lock Shubhix AI in recent apps."
        brand.contains("samsung") ->
            "1. Settings > Apps > Shubhix AI > Battery > Unrestricted.\n2. Settings > Battery > Background usage limits > Never sleeping apps > add Shubhix AI."
        brand.contains("nothing") ->
            "1. Settings > Apps > Shubhix AI > App battery usage > Unrestricted.\n2. Keep Shubhix AI locked in recent apps."
        else ->
            "1. Settings > Apps > Shubhix AI > Battery > Unrestricted (or Don't optimise).\n2. Lock Shubhix AI in the recent apps screen if your phone offers it."
    }

    /** Tries the brand's own autostart screen. Returns false when this phone has no such screen. */
    fun openAutostart(ctx: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
        )
        for (c in candidates) {
            try {
                ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // not on this phone: try the next one
            }
        }
        return false
    }
}
