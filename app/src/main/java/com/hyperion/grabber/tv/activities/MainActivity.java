package com.hyperion.grabber.tv.activities;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatDelegate;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.hyperion.grabber.common.BootActivity;
import com.hyperion.grabber.common.HyperionScreenService;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.R;

public class MainActivity extends LeanbackActivity implements ImageView.OnClickListener,
        ImageView.OnFocusChangeListener {
    public static final int REQUEST_MEDIA_PROJECTION = 1;
    public static final int REQUEST_INITIAL_SETUP = 2;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 3;
    private static final int REQUEST_AUDIO_PERMISSION = 4;
    public static final String BROADCAST_ERROR = "SERVICE_ERROR";
    public static final String BROADCAST_TAG = "SERVICE_STATUS";
    public static final String BROADCAST_FILTER = "SERVICE_FILTER";
    private static final String TAG = "DEBUG";
    private boolean mRecorderRunning = false;
    private static MediaProjectionManager mMediaProjectionManager;
    private int mPermissionDeniedCount = 0;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean checked = intent.getBooleanExtra(BROADCAST_TAG, false);
            mRecorderRunning = checked;
            String error = intent.getStringExtra(BROADCAST_ERROR);
            
            if (error != null) {
                Toast.makeText(getBaseContext(), error, Toast.LENGTH_SHORT).show();
            }
            setImageViews(checked, true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!initIfConfigured()){
            startSetup();
        }
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission();
        }
    }
    
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission is needed for the foreground service", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestScreenCapture();
            } else {
                Toast.makeText(this, "Microphone permission is needed for audio visualization mode", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** @return whether the activity was initialized */
    private boolean initIfConfigured() {
        // Do we have a valid server config?
        Preferences preferences = new Preferences(getApplicationContext());
        String host = preferences.getString(com.hyperion.grabber.common.R.string.pref_key_host, null);
        int port = preferences.getInt(com.hyperion.grabber.common.R.string.pref_key_port, -1);

        if (host == null || port == -1){
            return false;
        }

        initActivity();
        return true;
    }

    private void startSetup() {
        // Start onboarding (setup)
        Intent intent = new Intent(this, NetworkScanActivity.class);
        startActivityForResult(intent, REQUEST_INITIAL_SETUP);
    }

    // Prepare activity for display
    private void initActivity() {
        // assume the recorder is not running until we are notified otherwise
        mRecorderRunning = false;

        setContentView(R.layout.activity_main);
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        mMediaProjectionManager = (MediaProjectionManager)
                                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        ImageView iv = findViewById(R.id.power_toggle);
        iv.setOnClickListener(this);
        iv.setOnFocusChangeListener(this);
        iv.setFocusable(true);
        iv.requestFocus();

        ImageButton ib = findViewById(R.id.settingsButton);
        ib.setOnClickListener(this);
        ib.setOnFocusChangeListener(this);
        ib.setFocusable(true);

        setImageViews(mRecorderRunning, false);

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(BROADCAST_FILTER));

        // request an update on the running status
        checkForInstance();
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.power_toggle) {
            if (!mRecorderRunning) {
                requestScreenCapture();
            } else {
                stopScreenRecorder();
                mRecorderRunning = false;
            }
        } else if (id == R.id.settingsButton) {
            startSettings();
        }
    }
    
    private void requestScreenCapture() {
        // Audio visualization mode needs the microphone; ask before projection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isAudioOnlyMode()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }
        try {
            Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "Failed to request screen capture: " + e.getMessage());
            mPermissionDeniedCount++;
        }
    }

    private boolean isAudioOnlyMode() {
        Preferences prefs = new Preferences(getApplicationContext());
        return prefs.getBoolean(com.hyperion.grabber.common.R.string.pref_key_audio_only_mode, false);
    }

    @Override
    public void onFocusChange(View view, boolean focused) {
        int clr = Color.argb(255, 0, 0, 150);
        if (!focused) {
            clr = Color.argb(255, 0, 0, 0);
        }
        int id = view.getId();
        if (id == R.id.power_toggle) {
            ((ImageView) view).setColorFilter(clr);
        } else if (id == R.id.settingsButton) {
            ((ImageButton) view).setColorFilter(clr);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INITIAL_SETUP){
            if (resultCode == RESULT_OK){
                if (!initIfConfigured()){
                    startSetup();
                }
            } else {
                finish();
            }
            return;
        }
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                mPermissionDeniedCount++;
                Toast.makeText(this, "Permission denied. Tap again to retry.", Toast.LENGTH_SHORT).show();
                if (mRecorderRunning) {
                    stopScreenRecorder();
                }
                mRecorderRunning = false;
                setImageViews(false, true);
                return;
            }
            mPermissionDeniedCount = 0;
            Log.i(TAG, "Starting screen capture");
            startScreenRecorder(resultCode, (Intent) data.clone());
            mRecorderRunning = true;
        }
    }

    private void startSettings() {
        stopScreenRecorder();
        Intent intent = new Intent(this, ManualSetupActivity.class);
        Bundle bundle =
                ActivityOptionsCompat.makeSceneTransitionAnimation(this)
                        .toBundle();
        startActivity(intent, bundle);
    }

    private void checkForInstance() {
        if (isServiceRunning()) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.GET_STATUS);
            startService(intent);
        }
    }

    private void setImageViews(boolean running, boolean animated) {
        View rainbow = findViewById(R.id.sweepGradientView);
        View message = findViewById(R.id.grabberStartedText);
        if (running) {
            if (animated){
                fadeView(rainbow, true);
                fadeView(message, true);
            } else {
                rainbow.setVisibility(View.VISIBLE);
                message.setVisibility(View.VISIBLE);
            }
        } else {
            if (animated){
                fadeView(rainbow, false);
                fadeView(message, false);
            } else {
                rainbow.setVisibility(View.INVISIBLE);
                message.setVisibility(View.INVISIBLE);
            }
        }
    }

    public void startScreenRecorder(int resultCode, Intent data) {
        BootActivity.startScreenRecorder(this, resultCode, data);
    }

    public void stopScreenRecorder() {
        if (mRecorderRunning) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.ACTION_EXIT);
            startService(intent);
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        assert manager != null;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (HyperionScreenService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void fadeView(View view, boolean visible){
        float alpha = visible ? 1f : 0f;
        int endVisibility = visible ? View.VISIBLE : View.INVISIBLE;
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(alpha)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(endVisibility);
                    }
                })
                .start();


    }
}
