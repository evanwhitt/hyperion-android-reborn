package com.hyperion.grabber

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.TaskStackBuilder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hyperion.grabber.common.BootActivity
import com.hyperion.grabber.common.HyperionScreenService
import com.hyperion.grabber.common.util.Preferences

@RequiresApi(api = Build.VERSION_CODES.N)
class HyperionGrabberTileService : TileService() {
    private val REMOVE_LISTENER_DELAY = 10000L // 10 second delay to remove listener
    private val mHandle = Handler(Looper.getMainLooper())

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tile = qsTile ?: return
            val running = intent.getBooleanExtra(HyperionScreenService.BROADCAST_TAG, false)
            val error = intent.getStringExtra(HyperionScreenService.BROADCAST_ERROR)
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

            tile.updateTile()
            if (error != null) {
                Toast.makeText(baseContext, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val unregisterReceiverRunner = Runnable {
        LocalBroadcastManager.getInstance(this@HyperionGrabberTileService).unregisterReceiver(mMessageReceiver)
        mIsListening = false
    }

    override fun onStartListening() {
        super.onStartListening()
        mHandle.removeCallbacksAndMessages(null)
        if (!mIsListening) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, IntentFilter(HyperionScreenService.BROADCAST_FILTER)
            )
            mIsListening = true
        }
        if (isServiceRunning()) {
            val intent = Intent(this, HyperionScreenService::class.java)
            intent.action = HyperionScreenService.GET_STATUS
            startService(intent)
        } else {
            val tile = qsTile ?: return
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        mHandle.postDelayed(unregisterReceiverRunner, REMOVE_LISTENER_DELAY)
    }

    override fun onDestroy() {
        super.onDestroy()
        mIsListening = false
    }

    override fun onClick() {
        val tile = qsTile ?: return
        tile.updateTile()
        val tileState = tile.state
        if (tileState == Tile.STATE_ACTIVE) {
            val intent = Intent(this, HyperionScreenService::class.java)
            intent.action = HyperionScreenService.ACTION_EXIT
            startService(intent)
        } else {
            val runner = Runnable {
                val setupStarted = startSetupIfNeeded()
                if (!setupStarted) {
                    val i = Intent(this, BootActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                    or Intent.FLAG_ACTIVITY_NO_HISTORY
                        )
                    }
                    startActivityAndCollapse(i)
                }
            }
            if (isLocked) {
                unlockAndRun(runner)
            } else {
                runner.run()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (HyperionScreenService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /** Starts the Settings Activity if connection settings are missing
     * @return true if setup was started
     */
    private fun startSetupIfNeeded(): Boolean {
        val preferences = Preferences(applicationContext)
        val hostMissing = TextUtils.isEmpty(preferences.getString(com.hyperion.grabber.common.R.string.pref_key_host, null))
        val portMissing = preferences.getInt(com.hyperion.grabber.common.R.string.pref_key_port, -1) == -1

        if (hostMissing || portMissing) {
            val settingsIntent = Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_SHOW_TOAST_KEY, SettingsActivity.EXTRA_SHOW_TOAST_SETUP_REQUIRED_FOR_QUICK_TILE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(settingsIntent)
                .startActivities()

            val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            sendBroadcast(closeIntent)

            return true
        }
        return false
    }

    companion object {
        private var mIsListening = false
        
        @JvmStatic
        fun isListening(): Boolean = mIsListening
    }
}