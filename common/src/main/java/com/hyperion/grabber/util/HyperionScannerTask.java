package com.hyperion.grabber.common.util;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.util.Log;

import com.hyperion.grabber.common.network.NetworkScanner;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Starts a network scan for a running Hyperion server
 * and posts progress and results
 */
public class HyperionScannerTask {
    private WeakReference<Listener> weakListener;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public HyperionScannerTask(Listener listener){
        weakListener = new WeakReference<>(listener);
    }

    public void execute() {
        executor.execute(() -> {
            try {
                String result = doInBackground();
                mainHandler.post(() -> onPostExecute(result));
            } finally {
                shutdown();
            }
        });
    }
    
    /**
     * Shutdown the executor service to prevent thread leaks
     * Call this when you no longer need the scanner
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private String doInBackground() {
        Log.d("Hyperion scanner", "starting scan");
        NetworkScanner networkScanner = new NetworkScanner();

        String result;
        while (networkScanner.hasNextAttempt()){
            result = networkScanner.tryNext();

            if (result != null){
                return result;
            }

            final float progress = networkScanner.getProgress();
            mainHandler.post(() -> onProgressUpdate(progress));
        }

        return null;
    }

    private void onProgressUpdate(Float value) {
        Log.d("Hyperion scanner", "scan progress: " + value);
        // Cache the listener reference to avoid multiple get() calls
        Listener listener = weakListener.get();
        if (listener != null) {
            listener.onScannerProgress(value);
        }
    }

    private void onPostExecute(String result) {
        Log.d("Hyperion scanner", "scan result: " + result);
        // Cache the listener reference to avoid multiple get() calls
        Listener listener = weakListener.get();
        if (listener != null) {
            listener.onScannerCompleted(result);
        }
    }

    public interface Listener {

        void onScannerProgress(float progress);
        void onScannerCompleted(@Nullable String foundIpAddress);

    }
}