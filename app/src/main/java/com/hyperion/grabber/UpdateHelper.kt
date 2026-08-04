package com.hyperion.grabber

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hyperion.grabber.common.R
import com.hyperion.grabber.common.util.Preferences
import com.hyperion.grabber.common.util.UpdateChecker

/** Runs a background update check on app start and shows the dialog if one is available. */
object UpdateHelper {
    private const val TAG = "UpdateHelper"
    private const val CHECK_DELAY_MS = 2000L

    fun checkForUpdates(context: Context) {
        val currentVersion = getCurrentVersion(context) ?: return
        Thread {
            try {
                Thread.sleep(CHECK_DELAY_MS)
                val update = UpdateChecker().checkForUpdates(currentVersion) ?: return@Thread

                val prefs = Preferences(context)
                val lastNotified = prefs.getString(R.string.pref_key_last_update_notified, null)
                if (update.tagName == lastNotified) return@Thread

                Handler(Looper.getMainLooper()).post {
                    UpdateDialog(context).show(update) { dismissForever ->
                        if (dismissForever) {
                            prefs.putString(R.string.pref_key_last_update_notified, update.tagName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Update check failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun getCurrentVersion(context: Context): String? {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName
        } catch (e: Exception) {
            Log.e(TAG, "Could not read version", e)
            null
        }
    }
}
