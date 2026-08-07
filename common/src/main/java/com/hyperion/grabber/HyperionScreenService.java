package com.hyperion.grabber.common;

import android.annotation.TargetApi;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;
import com.hyperion.grabber.common.util.Preferences;

import java.util.Objects;

public class HyperionScreenService extends Service {
    public static final String BROADCAST_ERROR = "SERVICE_ERROR";
    public static final String BROADCAST_TAG = "SERVICE_STATUS";
    public static final String BROADCAST_FILTER = "SERVICE_FILTER";
    private static final boolean DEBUG = false;
    private static final String TAG = "HyperionScreenService";
    
    private boolean mForegroundFailed = false;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;

    private static final String BASE = "com.hyperion.grabber.service.";
    public static final String ACTION_START = BASE + "ACTION_START";
    public static final String ACTION_STOP = BASE + "ACTION_STOP";
    public static final String ACTION_EXIT = BASE + "ACTION_EXIT";
    public static final String GET_STATUS = BASE + "ACTION_STATUS";
    public static final String EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE";
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_EXIT_INTENT_ID = 2;
    private static final int NOTIFICATION_RESTART_INTENT_ID = 3;

    private boolean mReconnectEnabled = false;
    private boolean mHasConnected = false;
    private MediaProjectionManager mMediaProjectionManager;
    private HyperionThread mHyperionThread;
    private static MediaProjection sMediaProjection;
    private int mFrameRate;
    private int mHorizontalLEDCount;
    private int mVerticalLEDCount;
    private boolean mSendAverageColor;
    private HyperionScreenEncoderBase mHyperionEncoder;
    private NotificationManager mNotificationManager;
    private HyperionNotification mHyperionNotification;
    private String mStartError = null;
    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;
    private long mLastFpsPollCount;
    private long mLastFpsPollTimeNs;
    private boolean mRestartPending;
    private static final long STATUS_UPDATE_MS = 2000;
    private static final long STALL_TIMEOUT_MS = 30_000;
    private static final long WATCHDOG_INTERVAL_MS = 1000;

    private final Runnable mStatusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatusNotification();
            mHandler.postDelayed(this, STATUS_UPDATE_MS);
        }
    };

    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mHyperionEncoder != null && mHyperionEncoder.isCapturing() && mHasConnected) {
                long last = mHyperionEncoder.getLastFrameSentMs();
                if (last > 0 && System.currentTimeMillis() - last > STALL_TIMEOUT_MS) {
                    Log.w(TAG, "Capture stalled, restarting encoder");
                    restartEncoder();
                }
            }
            mHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private final HyperionThreadBroadcaster mReceiver = new HyperionThreadBroadcaster() {
        @Override
        public void onConnected() {
            if (DEBUG) Log.d(TAG, "Connected to Hyperion server");
            mHasConnected = true;
            notifyActivity();
        }

        @Override
        public void onConnectionError(int errorID, String error) {
            Log.e(TAG, "Connection error: " + (error != null ? error : "unknown"));
            if (!mHasConnected && !mReconnectEnabled) {
                mStartError = getResources().getString(R.string.error_server_unreachable);
                haltStartup();
            } else if (mReconnectEnabled) {
                Log.i(TAG, "Attempting automatic reconnect...");
            } else if (mHasConnected) {
                mStartError = getResources().getString(R.string.error_connection_lost);
                stopSelf();
            }
        }

        @Override
        public void onReceiveStatus(boolean isCapturing) {
            if (DEBUG) Log.v(TAG, "Received status: capturing=" + isCapturing);
            notifyActivity();
        }
    };

    BroadcastReceiver mEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getAction())) {
                case Intent.ACTION_SCREEN_ON:
                    if (mHyperionThread != null) mHyperionThread.resumeConnection();
                    if (mHyperionEncoder != null) mHyperionEncoder.resumeRecording();
                    notifyActivity();
                break;
                case Intent.ACTION_SCREEN_OFF:
                    if (mHyperionEncoder != null) mHyperionEncoder.pauseRecording();
                    if (mHyperionThread != null) mHyperionThread.pauseConnection();
                break;
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    if (DEBUG) Log.v(TAG, "ACTION_CONFIGURATION_CHANGED intent received");
                    if (mHyperionEncoder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        if (DEBUG) Log.v(TAG, "Configuration changed, checking orientation");
                        mHyperionEncoder.setOrientation(getResources().getConfiguration().orientation);
                    }
                break;
                case Intent.ACTION_SHUTDOWN:
                case Intent.ACTION_REBOOT:
                    if (DEBUG) Log.v(TAG, "ACTION_SHUTDOWN|ACTION_REBOOT intent received");
                    stopScreenRecord();
                break;
            }
        }
    };

    @Override
    public void onCreate() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        
        super.onCreate();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private boolean prepared() {
        Preferences prefs = new Preferences(getBaseContext());
        String host = prefs.getString(R.string.pref_key_host, null);
        int port = prefs.getInt(R.string.pref_key_port, -1);
        String priority = prefs.getString(R.string.pref_key_priority, "100");
        mFrameRate = prefs.getInt(R.string.pref_key_framerate);
        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led);
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led);
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color);
        mReconnectEnabled = prefs.getBoolean(R.string.pref_key_reconnect);
        int delay = prefs.getInt(R.string.pref_key_reconnect_delay);

        if (host == null || Objects.equals(host, "0.0.0.0") || Objects.equals(host, "")) {
            mStartError = getResources().getString(R.string.error_empty_host);
            return false;
        }
        if (port == -1) {
            mStartError = getResources().getString(R.string.error_empty_port);
            return false;
        }

        if (mHorizontalLEDCount <= 0 || mVerticalLEDCount <= 0) {
            mStartError = getResources().getString(R.string.error_invalid_led_counts);
            return false;
        }

        int priorityValue = 100;
        try {
            priorityValue = Integer.parseInt(priority);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid priority value: " + priority);
        }
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mHyperionThread = new HyperionThread(mReceiver, host, port, priorityValue, mReconnectEnabled, delay);
        mHyperionThread.start();
        mStartError = null;
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.v(TAG, "Start command received");
        super.onStartCommand(intent, flags, startId);
        if (intent == null || intent.getAction() == null) {
            String nullItem = (intent == null ? "intent" : "action");
            if (DEBUG) Log.v(TAG, "Null " + nullItem + " provided to start command");
        } else  {
            final String action = intent.getAction();
            if (DEBUG) Log.v(TAG, "Start command action: " + String.valueOf(action));
            switch (action) {
                case ACTION_START:
                    if (mHyperionThread == null) {
                        boolean isPrepared = prepared();
                        if (isPrepared) {
                            tryStartForeground();
                            acquireWakeLock();
                            
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        startScreenRecord(intent);
                                    } catch (SecurityException e) {
                                        Log.e(TAG, "Failed to start screen recording: " + e.getMessage());
                                        mStartError = getResources().getString(R.string.error_media_projection_denied);
                                        haltStartup();
                                    }
                                }
                            });
                            
                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
                            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                            intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                            intentFilter.addAction(Intent.ACTION_REBOOT);
                            intentFilter.addAction(Intent.ACTION_SHUTDOWN);

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                registerReceiver(mEventReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
                            } else {
                                registerReceiver(mEventReceiver, intentFilter);
                            }
                        } else {
                            haltStartup();
                        }
                    }
                    break;
                case ACTION_STOP:
                    stopScreenRecord();
                    stopSelf();
                    break;
                case GET_STATUS:
                    notifyActivity();
                    break;
                case ACTION_EXIT:
                    stopScreenRecord();
                    stopSelf();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.v(TAG, "Ending service");

        try {
            unregisterReceiver(mEventReceiver);
        } catch (Exception e) {
            if (DEBUG) Log.v(TAG, "Wake receiver not registered");
        }

        releaseWakeLock();
        stopScreenRecord();
        stopForeground(true);
        notifyActivity();

        super.onDestroy();
    }
    
    private void tryStartForeground() {
        mForegroundFailed = false;
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, getNotification());
            }
        } catch (ForegroundServiceStartNotAllowedException e) {
            Log.e(TAG, "Foreground service start not allowed: " + e.getMessage());
            mForegroundFailed = true;
            mHandler.postDelayed(this::retryForegroundStart, 100);
        } catch (Exception e) {
            Log.e(TAG, "Foreground start failed: " + e.getMessage());
            mForegroundFailed = true;
            mHandler.postDelayed(this::retryForegroundStart, 100);
        }
    }
    
    private void retryForegroundStart() {
        if (mForegroundFailed) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, getNotification());
                }
                mForegroundFailed = false;
                if (DEBUG) Log.d(TAG, "Foreground start successful on retry");
            } catch (Exception e) {
                Log.e(TAG, "Foreground retry failed: " + e.getMessage());
                mForegroundFailed = true;
                notifyForegroundFailed();
            }
        }
    }
    
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, 
                            "HyperionGrabber::ScreenCapture");
                    mWakeLock.acquire(60 * 60 * 1000L);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to acquire wake lock", e);
            }
        }
    }
    
    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
                Log.i(TAG, "Wake lock released");
            } catch (Exception e) {
                Log.e(TAG, "Failed to release wake lock", e);
            }
            mWakeLock = null;
        }
    }
    
    private void notifyForegroundFailed() {
        Intent intent = new Intent(BROADCAST_FILTER);
        intent.putExtra(BROADCAST_TAG, false);
        intent.putExtra(BROADCAST_ERROR, "Foreground service blocked by device manufacturer");
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    private void haltStartup() {
        // Try to start foreground to show error, but don't fail if blocked
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, getNotification());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start foreground during halt: " + e.getMessage());
        }
        
        notifyActivity();
        
        if (mHyperionThread != null) {
            mHyperionThread.interrupt();
            mHyperionThread = null;
        }
        
        stopSelf();
    }

    private Intent buildExitButton() {
        Intent notificationIntent = new Intent(this, this.getClass());
        notificationIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        notificationIntent.setAction(ACTION_EXIT);
        return notificationIntent;
    }

    public Notification getNotification() {
        if (mHyperionNotification == null) {
            mHyperionNotification = new HyperionNotification(this, mNotificationManager);
            String label = getString(R.string.notification_exit_button);
            mHyperionNotification.setAction(NOTIFICATION_EXIT_INTENT_ID, label, buildExitButton());
        }
        return mHyperionNotification.buildNotification();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScreenRecord(final Intent intent) {
        if (DEBUG) Log.v(TAG, "Starting screen recorder");
        
        if (mMediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager not initialized");
            mStartError = "Failed to initialize media projection manager";
            return;
        }

        if (mHyperionThread == null) {
            Log.e(TAG, "HyperionThread is null, cannot start screen recording");
            mStartError = getResources().getString(R.string.error_server_unreachable);
            return;
        }
        final HyperionThread thread = mHyperionThread;

        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        final MediaProjection projection = mMediaProjectionManager.getMediaProjection(resultCode, intent);
        
        if (projection == null) {
            Log.e(TAG, "Failed to create MediaProjection - permission may have been denied");
            mStartError = "Failed to obtain media projection";
            haltStartup();
            return;
        }
        
        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (window == null) {
            Log.e(TAG, "WindowManager not available");
            mStartError = "Failed to get window manager";
            projection.stop();
            return;
        }
        
        sMediaProjection = projection;
        final DisplayMetrics metrics = new DisplayMetrics();
        window.getDefaultDisplay().getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        mScreenDensity = metrics.densityDpi;

        Preferences prefs = new Preferences(getBaseContext());
        HyperionGrabberOptions options = new HyperionGrabberOptions(
                mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor,
                captureSizeIndex(prefs.getString(R.string.pref_key_capture_resolution, "medium")));
         
        if (DEBUG) Log.v(TAG, "Creating encoder: " + metrics.widthPixels + "x" + metrics.heightPixels);

        // "codec" method works around devices that return black frames when a
        // VirtualDisplay feeds an ImageReader directly (TCL, Amlogic, etc.).
        String captureMethod = prefs.getString(R.string.pref_key_capture_method, "imagereader");

        mHyperionEncoder = createEncoder(thread, projection, metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi, options, captureMethod);

        mHyperionEncoder.sendStatus();
        mRestartPending = false;
        mHandler.removeCallbacks(mStatusUpdater);
        mHandler.removeCallbacks(mWatchdog);
        mHandler.post(mStatusUpdater);
        mHandler.post(mWatchdog);
     }

    private HyperionScreenEncoderBase createEncoder(HyperionThread thread, MediaProjection projection,
            int width, int height, int density, HyperionGrabberOptions options, String captureMethod) {
        HyperionScreenEncoderBase encoder = null;
        if ("codec".equals(captureMethod)) {
            try {
                encoder = new HyperionCodecScreenEncoder(
                        thread.getReceiver(),
                        projection,
                        width, height, density,
                        options,
                        this);
                Log.i(TAG, "Using codec capture method");
            } catch (Exception e) {
                Log.e(TAG, "Codec capture failed to initialize, falling back to ImageReader: " + e.getMessage());
                encoder = null;
            }
        }

        if (encoder == null) {
            encoder = new HyperionScreenEncoder(
                    thread.getReceiver(),
                    projection,
                    width, height, density,
                    options,
                    this);
            // If this path returns persistent black frames (common on some TVs),
            // automatically switch to the Codec capture method.
            ((HyperionScreenEncoder) encoder).setBlackFrameCallback(() -> {
                mHandler.post(HyperionScreenService.this::restartWithCodec);
            });
        }
        encoder.setWhiteFrameCallback(() -> mHandler.post(this::warnHdrWhiteFrames));
        return encoder;
    }

    /** Switches from the ImageReader capture path to the Codec path after black frames. */
    private void restartWithCodec() {
        if (mHyperionEncoder == null || !(mHyperionEncoder instanceof HyperionScreenEncoder)) {
            return;
        }
        Log.i(TAG, "Black frames detected, switching to Codec capture method");
        requestCaptureRestart("codec");
    }

    private void restartEncoder() {
        if (mHyperionEncoder == null || sMediaProjection == null) {
            return;
        }
        Log.w(TAG, "Capture stalled, restarting");
        Preferences prefs = new Preferences(getBaseContext());
        requestCaptureRestart(prefs.getString(R.string.pref_key_capture_method, "imagereader"));
    }

    /**
     * A MediaProjection only supports a single VirtualDisplay, so the pipeline
     * cannot be recreated in place. Tear everything down and ask the user to
     * re-grant screen capture via the notification action (background activity
     * starts are blocked by Android, so this has to go through the notification).
     */
    private void requestCaptureRestart(String captureMethod) {
        Preferences prefs = new Preferences(getBaseContext());
        prefs.putString(R.string.pref_key_capture_method, captureMethod);
        Log.i(TAG, "Requesting capture restart with method " + captureMethod);
        mStartError = null;
        mRestartPending = true;

        if (mHyperionEncoder != null) {
            mHyperionEncoder.stopRecording();
            mHyperionEncoder = null;
        }
        if (mHyperionThread != null) {
            mHyperionThread.interrupt();
            mHyperionThread = null;
        }
        sMediaProjection = null;
        mHasConnected = false;

        updateStatusNotification();
        notifyActivity();
    }

    private Intent buildRestartButton() {
        Intent intent = new Intent(this, ToggleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(ToggleActivity.EXTRA_RESTART, true);
        return intent;
    }

    private void updateStatusNotification() {
        if (mHyperionNotification == null) {
            return;
        }
        mHyperionNotification.clearActions();
        mHyperionNotification.setAction(NOTIFICATION_EXIT_INTENT_ID,
                getString(R.string.notification_exit_button), buildExitButton());
        if (mRestartPending) {
            mHyperionNotification.setActivityAction(NOTIFICATION_RESTART_INTENT_ID,
                    getString(R.string.notification_restart_button), buildRestartButton());
            mNotificationManager.notify(NOTIFICATION_ID,
                    mHyperionNotification.buildNotification(getString(R.string.notification_status_restart)));
            return;
        }
        if (mHyperionEncoder == null) {
            mNotificationManager.notify(NOTIFICATION_ID,
                    mHyperionNotification.buildNotification(getString(R.string.notification_status_restart)));
            return;
        }
        int w = mHyperionEncoder.getCaptureWidth();
        int h = mHyperionEncoder.getCaptureHeight();
        long count = mHyperionEncoder.getSentFrameCount();
        long nowNs = System.nanoTime();
        float fps = 0f;
        if (mLastFpsPollCount >= 0 && count >= mLastFpsPollCount) {
            long dtMs = (nowNs - mLastFpsPollTimeNs) / 1_000_000L;
            if (dtMs > 0) {
                fps = (count - mLastFpsPollCount) * 1000f / dtMs;
            }
        }
        mLastFpsPollCount = count;
        mLastFpsPollTimeNs = nowNs;

        String text;
        if (isCommunicating()) {
            text = String.format(java.util.Locale.US, getString(R.string.notification_status_active),
                    w, h, fps);
        } else if (mHyperionEncoder.isCapturing()) {
            text = String.format(java.util.Locale.US, getString(R.string.notification_status_reconnecting),
                    w, h);
        } else {
            text = String.format(java.util.Locale.US, getString(R.string.notification_status_paused),
                    w, h);
        }
        try {
            mNotificationManager.notify(NOTIFICATION_ID, mHyperionNotification.buildNotification(text));
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Failed to update notification: " + e.getMessage());
        }
    }

    /** Maps the capture resolution preference to a HyperionGrabberOptions tier index. */
    private static int captureSizeIndex(String value) {
        if ("low".equals(value)) return 0;
        if ("high".equals(value)) return 2;
        return 1; // medium
    }

    private void warnHdrWhiteFrames() {
        Log.w(TAG, "Persistent white frames detected, likely HDR video the capture path can't decode");
        String message = getString(R.string.hdr_white_frames_warning);
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
        Intent intent = new Intent(BROADCAST_FILTER);
        intent.putExtra(BROADCAST_TAG, isCommunicating());
        intent.putExtra(BROADCAST_ERROR, message);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    private void stopScreenRecord() {
        if (DEBUG) Log.v(TAG, "Stopping screen recorder");
        mReconnectEnabled = false;
        mNotificationManager.cancel(NOTIFICATION_ID);
        mHandler.removeCallbacks(mStatusUpdater);
        mHandler.removeCallbacks(mWatchdog);
        
        if (mHyperionEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping encoder");
            mHyperionEncoder.stopRecording();
            mHyperionEncoder = null;
        }
        
        releaseResource();
        
        if (mHyperionThread != null) {
            mHyperionThread.interrupt();
            mHyperionThread = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void releaseResource() {
        if (sMediaProjection != null) {
            sMediaProjection.stop();
            sMediaProjection = null;
        }
    }

    boolean isCapturing() {
        return mHyperionEncoder != null && mHyperionEncoder.isCapturing();
    }

    boolean isCommunicating() {
        return isCapturing() && mHasConnected;
    }

    private void notifyActivity() {
        Intent intent = new Intent(BROADCAST_FILTER);
        intent.putExtra(BROADCAST_TAG, isCommunicating());
        intent.putExtra(BROADCAST_ERROR, mStartError);
        if (DEBUG) {
            Log.v(TAG, "Broadcasting status: communicating=" + isCommunicating() + 
                    (mStartError != null ? ", error=" + mStartError : ""));
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public interface HyperionThreadBroadcaster {
//        void onResponse(String response);
        void onConnected();
        void onConnectionError(int errorHash, String errorString);
        void onReceiveStatus(boolean isCapturing);
    }
}
