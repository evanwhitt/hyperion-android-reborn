package com.hyperion.grabber.common;

import android.content.res.Configuration;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.BorderProcessor;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;

abstract class HyperionScreenEncoderBase {
    private static final String TAG = "ScreenEncoderBase";
    static final boolean DEBUG = false;
    
    private static final int CLEAR_DELAY_MS = 100;

    // Configuration (immutable after construction)
    protected final int mDensity;
    protected final int mFrameRate;
    protected final boolean mAvgColor;
    protected final boolean mRemoveBorders = false; // Disabled for now
    private final int mInitOrientation;
    private final int mWidthScaled;
    private final int mHeightScaled;

    // Components
    protected final MediaProjection mMediaProjection;
    protected final HyperionThread.HyperionThreadListener mListener;
    protected final BorderProcessor mBorderProcessor;
    protected final Handler mHandler;
    
    // Mutable state
    protected volatile int mCurrentOrientation;
    private volatile boolean mIsCapturing;
    protected Runnable mWhiteFrameCallback;
    private volatile long mSentFrameCount;
    private volatile long mLastFrameSentMs;

    public void setWhiteFrameCallback(Runnable callback) {
        mWhiteFrameCallback = callback;
    }

    protected void markFrameSent() {
        mSentFrameCount++;
        mLastFrameSentMs = System.currentTimeMillis();
    }

    public long getSentFrameCount() {
        return mSentFrameCount;
    }

    public long getLastFrameSentMs() {
        return mLastFrameSentMs;
    }

    public int getCaptureWidth() {
        return getGrabberWidth();
    }

    public int getCaptureHeight() {
        return getGrabberHeight();
    }

    HyperionScreenEncoderBase(HyperionThread.HyperionThreadListener listener,
                              MediaProjection projection,
                              int width, int height,
                              int density,
                              HyperionGrabberOptions options) {
        mListener = listener;
        mMediaProjection = projection;
        mDensity = density;
        mFrameRate = options.getFrameRate();
        mAvgColor = options.useAverageColor();
        mBorderProcessor = new BorderProcessor(options.getBlackThreshold());

        // Determine orientation
        mCurrentOrientation = mInitOrientation = 
                width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;

        // Calculate scaled dimensions
        final int divisor = options.findDivisor(width, height);
        mWidthScaled = width / divisor;
        mHeightScaled = height / divisor;

        // Handler thread for callbacks
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        if (DEBUG) {
            Log.d(TAG, "Init: " + width + "x" + height + " -> " + mWidthScaled + "x" + mHeightScaled);
        }
    }

    public void clearLights() {
        // Use Handler instead of creating a new Thread - more efficient
        mHandler.postDelayed(() -> {
            try {
                mListener.clear();
            } catch (Exception e) {
                Log.e(TAG, "Error clearing lights: " + e.getMessage());
            }
        }, CLEAR_DELAY_MS);
    }

    protected void clearAndDisconnect() {
        // Runs the clear/disconnect and then safely stops the handler looper
        // so the queued work is not dropped.
        mHandler.post(() -> {
            try {
                mListener.clear();
                mListener.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error clearing and disconnecting: " + e.getMessage());
            } finally {
                mHandler.getLooper().quitSafely();
            }
        });
    }
    
    public boolean isCapturing() {
        return mIsCapturing;
    }

    protected void setCapturing(boolean capturing) {
        mIsCapturing = capturing;
    }

    public void sendStatus() {
        mListener.sendStatus(mIsCapturing);
    }

    protected int getGrabberWidth() {
        return mInitOrientation != mCurrentOrientation ? mHeightScaled : mWidthScaled;
    }

    protected int getGrabberHeight() {
        return mInitOrientation != mCurrentOrientation ? mWidthScaled : mHeightScaled;
    }

    public abstract void stopRecording();
    public abstract void pauseRecording();
    public abstract void resumeRecording();
    public abstract void setOrientation(int orientation);
}
