package com.hyperion.grabber

import android.app.AlertDialog
import android.content.Context
import com.hyperion.grabber.common.util.GithubRelease
import com.hyperion.grabber.common.util.UpdateManager

/**
 * Prompts the user about a new release.
 * @param onDismiss called when the dialog closes. Pass `true` if this version
 *        should not be offered again (Update / Later / back), `false` to keep
 *        reminding on the next launch.
 */
class UpdateDialog(private val context: Context) {

    fun show(release: GithubRelease, onDismiss: (dismissForever: Boolean) -> Unit) {
        try {
            val notes = release.body
                .ifEmpty { "Bug fixes and improvements" }
                .let { if (it.length > 600) it.take(600) + "..." else it }

            AlertDialog.Builder(context)
                .setTitle("Update available")
                .setMessage("Version ${release.tagName} is available.\n\n$notes")
                .setPositiveButton("Update") { dialog, _ ->
                    dialog.dismiss()
                    UpdateManager(context).downloadAndInstall(release.downloadUrl, release.tagName) {}
                    onDismiss(true)
                }
                .setNegativeButton("Later") { dialog, _ ->
                    dialog.dismiss()
                    onDismiss(true)
                }
                .setNeutralButton("Remind me next launch") { dialog, _ ->
                    dialog.dismiss()
                    onDismiss(false)
                }
                .setOnCancelListener { onDismiss(true) }
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
