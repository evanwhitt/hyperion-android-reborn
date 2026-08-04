package com.hyperion.grabber.common.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Downloads a release APK and kicks off the installer. */
class UpdateManager(private val context: Context) {
    private val TAG = "UpdateManager"
    private var downloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    fun downloadAndInstall(downloadUrl: String, versionName: String, onComplete: (Boolean) -> Unit) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val fileName = "hyperion-grabber-${versionName.replace("v", "").replace(" ", "-")}.apk"

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Hyperion Grabber Update")
                setDescription("Downloading $versionName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, null, "updates/$fileName")
                setMimeType("application/vnd.android.package-archive")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            downloadId = downloadManager.enqueue(request)
            Log.d(TAG, "Download started: $downloadId from $downloadUrl")
            showToast("Downloading update...")

            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (id != downloadId) return
                    unregisterReceiver()

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    var success = false
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        success = status == DownloadManager.STATUS_SUCCESSFUL
                    }
                    cursor?.close()

                    if (success) {
                        showToast("Download complete, installing...")
                        installApk(fileName)
                        onComplete(true)
                    } else {
                        Log.e(TAG, "Download failed")
                        showToast("Download failed")
                        onComplete(false)
                    }
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            // The completion broadcast is sent by the DownloadProvider app, so the
            // receiver must be exported to receive it. It's keyed to our download id.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(downloadReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            showToast("Download failed: ${e.message}")
            onComplete(false)
        }
    }

    private fun installApk(fileName: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "updates/$fileName")
            if (!file.exists()) {
                Log.e(TAG, "APK file not found: ${file.absolutePath}")
                showToast("Downloaded file not found")
                return
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            Log.d(TAG, "Install intent launched for: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            showToast("Install failed: ${e.message}")
        }
    }

    private fun unregisterReceiver() {
        try {
            downloadReceiver?.let { context.unregisterReceiver(it) }
            downloadReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered")
        }
    }

    private fun showToast(message: String) {
        handler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    fun cancelDownload() {
        if (downloadId != -1L) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(downloadId)
                unregisterReceiver()
            } catch (e: Exception) {
                Log.e(TAG, "Cancel error", e)
            }
        }
    }
}
