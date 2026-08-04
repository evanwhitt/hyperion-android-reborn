package com.hyperion.grabber;

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
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.hyperion.grabber.common.BootActivity;
import com.hyperion.grabber.common.HyperionScreenService;
import com.hyperion.grabber.common.util.PermissionHelper;

public class MainActivity extends AppCompatActivity implements ImageView.OnClickListener,
        ImageView.OnFocusChangeListener {
    public static final int REQUEST_MEDIA_PROJECTION = 1;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2;
    private static final int REQUEST_AUDIO_PERMISSION = 3;
    private static final String TAG = "DEBUG";
    private boolean mRecorderRunning = false;
    private static MediaProjectionManager mMediaProjectionManager;
    private Intent mPendingProjectionData = null;
    private int mPendingResultCode = 0;
    private int mPermissionDeniedCount = 0;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean checked = intent.getBooleanExtra(HyperionScreenService.BROADCAST_TAG, false);
            mRecorderRunning = checked;
            String error = intent.getStringExtra(HyperionScreenService.BROADCAST_ERROR);
            
            if (error != null &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                            !HyperionGrabberTileService.isListening())) {
                Toast.makeText(getBaseContext(), error, Toast.LENGTH_LONG).show();
            }
            setImageViews(checked, checked);
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Remove update check logic
        mMediaProjectionManager = (MediaProjectionManager)
                                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        ImageView iv = findViewById(R.id.power_toggle);
        iv.setOnClickListener(this);
        iv.setOnFocusChangeListener(this);
        iv.setFocusable(true);
        iv.requestFocus();

        View settingsButton = findViewById(R.id.settingsButton);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
            });
        }

        setImageViews(mRecorderRunning, false);

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(HyperionScreenService.BROADCAST_FILTER));
        checkForInstance();
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
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

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                mPermissionDeniedCount++;
                mRecorderRunning = false;
                if (mPermissionDeniedCount >= 2) {
                    PermissionHelper.showFullPermissionDialog(this, this::requestScreenCapture);
                } else {
                    Toast.makeText(this, "Screen recording permission was denied. Tap again to retry.", Toast.LENGTH_LONG).show();
                }
                setImageViews(false, true);
                return;
            }
            
            // Null check for Intent data - required for non-null startScreenRecorder
            if (data == null) {
                Log.e(TAG, "Screen projection result intent is null");
                Toast.makeText(this, "Failed to obtain screen recording data", Toast.LENGTH_LONG).show();
                setImageViews(false, true);
                return;
            }
            
            mPermissionDeniedCount = 0;
            Log.i(TAG, "Starting screen capture");
            try {
                startScreenRecorder(resultCode, (Intent) data.clone());
                mRecorderRunning = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start screen recorder: " + e.getMessage());
                Toast.makeText(this, "Failed to start recording: " + e.getMessage(), Toast.LENGTH_LONG).show();
                setImageViews(false, true);
            }
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
        } catch (SecurityException e) {
            Log.e(TAG, "Screen capture permission denied: " + e.getMessage());
            mPermissionDeniedCount++;
            PermissionHelper.showFullPermissionDialog(this, this::requestScreenCapture);
            setImageViews(false, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to request screen capture: " + e.getMessage());
            Toast.makeText(this, "Failed to request screen recording: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setImageViews(false, true);
        }
    }

    private boolean isAudioOnlyMode() {
        com.hyperion.grabber.common.util.Preferences prefs =
                new com.hyperion.grabber.common.util.Preferences(this);
        return prefs.getBoolean(com.hyperion.grabber.common.R.string.pref_key_audio_only_mode, false);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.power_toggle) {
            if (mRecorderRunning) {
                stopScreenRecorder();
            } else {
                requestScreenCapture();
            }
        }
    }

    @Override
    public void onFocusChange(View view, boolean focused) {
        if (focused) {
            ((ImageView) view).setColorFilter(Color.argb(255, 0, 0, 150));
        } else {
            ((ImageView) view).setColorFilter(Color.argb(255, 0, 0, 0));
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkForInstance() {
        if (isServiceRunning()) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.GET_STATUS);
            startService(intent);
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

    private void setImageViews(boolean running, boolean animated) {
        View rainbow = findViewById(R.id.sweepGradientView);
        View message = findViewById(R.id.grabberStartedText);
        View buttonImage = findViewById(R.id.power_toggle);
        if (running) {
            if (animated){
                fadeView(rainbow, true);
                fadeView(message, true);
            } else {
                rainbow.setVisibility(View.VISIBLE);
                message.setVisibility(View.VISIBLE);
            }
            buttonImage.setAlpha((float) 1);
        } else {
            if (animated){
                fadeView(rainbow, false);
                fadeView(message, false);
            } else {
                rainbow.setVisibility(View.INVISIBLE);
                message.setVisibility(View.INVISIBLE);
            }
            buttonImage.setAlpha((float) 0.25);
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            Log.w(TAG, "ActivityManager is null, cannot check service status");
            return false;
        }
        
        try {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (HyperionScreenService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if service is running: " + e.getMessage());
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
