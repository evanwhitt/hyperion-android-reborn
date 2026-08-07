package com.hyperion.grabber.common;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

public class ToggleActivity extends AppCompatActivity {
    public static final int REQUEST_MEDIA_PROJECTION = 1;
    public static final String EXTRA_RESTART = "restart_capture";

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean restart = getIntent().getBooleanExtra(EXTRA_RESTART, false);
        boolean serviceRunning = checkForInstance();

        if (serviceRunning && !restart) {
            stopService();
            finish();
        } else {
            requestPermission();
        }

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK) {
                startScreenRecorder(this, resultCode, data);
            }

            finish();

        }
    }

    private static void startScreenRecorder(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, HyperionScreenService.class);
        intent.setAction(HyperionScreenService.ACTION_START);
        intent.putExtra(HyperionScreenService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtras(data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** @return whether the service is running */
    private boolean checkForInstance() {
        if (isServiceRunning()) {
            Intent intent = new Intent(this, HyperionScreenService.class);
            intent.setAction(HyperionScreenService.GET_STATUS);
            startService(intent);
            return true;
        } else {
            return false;
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

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void requestPermission(){
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }
    }

    /** stop recording & stop service */

     private void stopService() {
        Intent stopIntent = new Intent(ToggleActivity.this, HyperionScreenService.class);
        stopIntent.setAction(HyperionScreenService.ACTION_EXIT);
        startService(stopIntent);
    }
}
