package com.hyperion.grabber.common;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hyperion.grabber.common.util.Diagnostics;

import java.util.Locale;

public class DiagnosticsActivity extends AppCompatActivity {

    private TextView mStats;
    private ImageView mPreview;
    private TextView mLogs;

    private final BroadcastReceiver mDiagReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String method = intent.getStringExtra(HyperionScreenService.DIAG_METHOD);
            int cw = intent.getIntExtra(HyperionScreenService.DIAG_CAPTURE_W, 0);
            int ch = intent.getIntExtra(HyperionScreenService.DIAG_CAPTURE_H, 0);
            int gw = intent.getIntExtra(HyperionScreenService.DIAG_GRID_W, 0);
            int gh = intent.getIntExtra(HyperionScreenService.DIAG_GRID_H, 0);
            float fps = intent.getFloatExtra(HyperionScreenService.DIAG_FPS, 0f);
            boolean connected = intent.getBooleanExtra(HyperionScreenService.DIAG_CONNECTED, false);
            byte[] frame = intent.getByteArrayExtra(HyperionScreenService.DIAG_FRAME);
            int fw = intent.getIntExtra(HyperionScreenService.DIAG_FRAME_W, 0);
            int fh = intent.getIntExtra(HyperionScreenService.DIAG_FRAME_H, 0);

            mStats.setText(String.format(Locale.US,
                    "Method: %s\nCapture: %dx%d\nLED grid: %dx%d\nFPS: %.1f\nServer: %s",
                    method == null ? "none" : method, cw, ch, gw, gh, fps,
                    connected ? "connected" : "not connected"));

            if (frame != null && fw > 0 && fh > 0) {
                mPreview.setImageBitmap(rgbToBitmap(frame, fw, fh));
            }
            mLogs.setText(Diagnostics.dump());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mStats = new TextView(this);
        mPreview = new ImageView(this);
        mLogs = new TextView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        mStats.setTextSize(16);
        mStats.setGravity(Gravity.CENTER);

        mPreview.setBackgroundColor(0xFF222222);
        mPreview.setAdjustViewBounds(true);
        mPreview.setMaxHeight(600);

        mLogs.setTextSize(11);
        mLogs.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(mLogs, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button copy = new Button(this);
        copy.setText(getString(R.string.diagnostics_copy));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("hyperion-diagnostics",
                        mStats.getText() + "\n\n" + Diagnostics.dump()));
            }
        });

        root.addView(mStats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        setTitle(getString(R.string.diagnostics_title));

        LocalBroadcastManager.getInstance(this).registerReceiver(mDiagReceiver,
                new IntentFilter(HyperionScreenService.DIAG_ACTION));
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mDiagReceiver);
        super.onDestroy();
    }

    private static Bitmap rgbToBitmap(byte[] rgb, int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int r = rgb[i * 3] & 0xFF;
            int g = rgb[i * 3 + 1] & 0xFF;
            int b = rgb[i * 3 + 2] & 0xFF;
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }
}
