package com.hyperion.grabber.common;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.RequiresApi;
import android.util.Log;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.BorderProcessor;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;
import com.hyperion.grabber.common.util.AnimationSyncController;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.common.util.AudioVisualizerController;

import java.nio.ByteBuffer;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = false;
    
    private static final int IMAGE_READER_IMAGES = 2;
    private static final int BORDER_CHECK_FRAMES = 60;
    private static final int BYTES_PER_PIXEL_RGBA = 4;
    private static final int BYTES_PER_PIXEL_RGB = 3;
    private static final int RGB_BUFFER_RING_SIZE = 3;
    
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private volatile boolean mRunning;
    private int mCaptureWidth;
    private int mCaptureHeight;
    private byte[] mRowBuffer;
    private final byte[] mAvgColorResult = new byte[3];
    private int mBorderX;
    private int mBorderY;
    private int mFrameCount;
    
    private byte[][] mRgbBufferRing = new byte[RGB_BUFFER_RING_SIZE][];
    private int mRgbBufferIndex = 0;
    
    private int mLastCachedBorderX = -1;
    private int mLastCachedBorderY = -1;
    
    private long mLastCaptureTimeNs = 0;
    private int mHighLoadCount = 0;
    private static final int HIGH_LOAD_THRESHOLD = 3;
    private static final double DEADLINE_MISS_RATIO = 0.85;
    
    private AnimationSyncController mAnimationSync;
    private Preferences mPreferences;
    private AudioVisualizerController mAudioVisualizer;
    private boolean mAudioOnlyMode = false;
    private boolean mAnimationAutoEnabled = false;
    private final int mMaxCaptureDimension;
    
    private final Runnable mCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            
            final long start = System.nanoTime();
            captureFrame();
            
            if (mRunning && mCaptureHandler != null) {
                final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                long effectiveDelayMs = mFrameIntervalMs - elapsedMs;
                
                if (mAnimationSync != null) {
                    effectiveDelayMs += mAnimationSync.getEffectiveFrameDelayNs() / 1_000_000L;
                }
                
                final long delayMs = Math.max(1L, effectiveDelayMs);
                mCaptureHandler.postDelayed(this, delayMs);
            }
        }
    };
    
    private final long mFrameIntervalMs;
    
    private final VirtualDisplay.Callback mDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            if (DEBUG) Log.d(TAG, "Display paused");
        }

        @Override
        public void onResumed() {
            if (DEBUG) Log.d(TAG, "Display resumed");
            if (!mRunning) startCapture();
        }

        @Override
        public void onStopped() {
            if (DEBUG) Log.d(TAG, "Display stopped");
            mRunning = false;
            setCapturing(false);
        }
    };

    /**
     * Creates a new screen encoder.
     */
    HyperionScreenEncoder(HyperionThread.HyperionThreadListener listener,
                          MediaProjection projection,
                          int screenWidth, int screenHeight,
                          int density,
                          HyperionGrabberOptions options,
                          Context context) {
        super(listener, projection, screenWidth, screenHeight, density, options);

        mFrameIntervalMs = 1000L / mFrameRate;
        mMaxCaptureDimension = options.getImageReaderMaxDimension();
        initCaptureDimensions();
        
        if (context != null) {
            mPreferences = new Preferences(context);
            int frameDelay = mPreferences.getInt(R.string.pref_key_frame_delay, 0);
            boolean enableAnimation = mPreferences.getBoolean(R.string.pref_key_enable_animation, false);
            mAudioOnlyMode = mPreferences.getBoolean(R.string.pref_key_audio_only_mode, false);
            
            mAnimationSync = new AnimationSyncController(frameDelay, enableAnimation);
            
            if (mAudioOnlyMode) {
                mAudioVisualizer = new AudioVisualizerController();
                mAnimationAutoEnabled = true;
            }
        } else {
            mAnimationSync = new AnimationSyncController(0, false);
        }
        
        if (DEBUG) Log.d(TAG, "Capture: " + mCaptureWidth + "x" + mCaptureHeight + " @ " + mFrameRate + "fps");
        if (mAudioOnlyMode) Log.d(TAG, "Audio-only visualization mode enabled");
        
        try {
            init();
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "Init failed", e);
        }
    }
    
    private void initCaptureDimensions() {
        final int maxW = mMaxCaptureDimension;
        final int maxH = Math.max(4, Math.round(maxW * (float) getGrabberHeight() / Math.max(1, getGrabberWidth())));
        int w = Math.max(4, Math.min(getGrabberWidth(), maxW));
        int h = Math.max(4, Math.min(getGrabberHeight(), maxH));
        mCaptureWidth = w & ~1;
        mCaptureHeight = h & ~1;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void init() throws MediaCodec.CodecException {
        mCaptureThread = new HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
            }
        }, mHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                mCaptureWidth, mCaptureHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(),
                mDisplayCallback,
                mHandler);

        startCapture();
    }

    private void startCapture() {
        mRunning = true;
        setCapturing(true);
        mFrameCount = 0;
        
        if (mAudioOnlyMode && mAudioVisualizer != null) {
            mAudioVisualizer.start();
            if (mAnimationSync != null && mAnimationAutoEnabled) {
                mAnimationSync.setAnimationEnabled(true);
            }
        }
        
        mCaptureHandler.post(mCaptureRunnable);
    }
    
    private void captureFrame() {
        long frameStart = System.nanoTime();
        
        if (mAudioOnlyMode && mAudioVisualizer != null) {
            generateAudioVisualization();
            long captureTime = System.nanoTime() - frameStart;
            mLastCaptureTimeNs = captureTime;
            updateLoadTracking(captureTime);
            return;
        }
        
        Image img = null;
        try {
            img = mImageReader.acquireLatestImage();
            if (img != null) {
                boolean skipBorderDetection = mHighLoadCount > 0;
                boolean skipAverageColor = mHighLoadCount > HIGH_LOAD_THRESHOLD;
                processImage(img, skipBorderDetection, skipAverageColor);
            }
        } catch (IllegalStateException e) {
            if (DEBUG) Log.w(TAG, "ImageReader is closed, stopping capture");
            mRunning = false;
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Capture error: " + e.getMessage(), e);
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close image: " + e.getMessage());
                }
            }
            long captureTime = System.nanoTime() - frameStart;
            mLastCaptureTimeNs = captureTime;
            updateLoadTracking(captureTime);
        }
    }
    
    private void generateAudioVisualization() {
        if (mAudioVisualizer == null) return;
        
        int[] spectrum = mAudioVisualizer.getFullSpectrum();
        int[] visualization = AudioVisualizerController.generateAudioVisualization(
                mCaptureWidth, mCaptureHeight, spectrum);
        
        byte[] rgb = getRgbBuffer(mCaptureWidth, mCaptureHeight);
        
        for (int i = 0; i < visualization.length && i < rgb.length; i++) {
            rgb[i] = (byte) (visualization[i] & 0xFF);
        }
        
        if (mAnimationSync != null) {
            rgb = mAnimationSync.applyAnimationToFrame(rgb);
        }
        
        mListener.sendFrame(rgb, mCaptureWidth, mCaptureHeight);
        mRgbBufferIndex = (mRgbBufferIndex + 1) % RGB_BUFFER_RING_SIZE;
    }

    private void updateLoadTracking(long captureTimeNs) {
        if (captureTimeNs > (long)(mFrameIntervalMs * 1_000_000 * DEADLINE_MISS_RATIO)) {
            mHighLoadCount = Math.min(5, mHighLoadCount + 1);
        } else {
            mHighLoadCount = Math.max(0, mHighLoadCount - 1);
        }
    }

    private void processImage(Image img, boolean skipBorderDetection, boolean skipAverageColor) {
        final Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return;
        
        final Image.Plane plane = planes[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int width = img.getWidth();
        final int height = img.getHeight();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        
        if (!skipBorderDetection) {
            updateBorderDetection(buffer, width, height, rowStride, pixelStride);
        }
        
        if (mAvgColor && !skipAverageColor) {
            sendAverageColor(buffer, width, height, rowStride, pixelStride);
        } else {
            sendPixelData(buffer, width, height, rowStride, pixelStride);
        }
    }
    
    private void updateBorderDetection(ByteBuffer buffer, int width, int height, 
                                        int rowStride, int pixelStride) {
        if (!mRemoveBorders && !mAvgColor) return;
        
        if (++mFrameCount >= BORDER_CHECK_FRAMES) {
            mFrameCount = 0;
            mBorderProcessor.parseBorder(buffer, width, height, rowStride, pixelStride);
            final BorderProcessor.BorderObject border = mBorderProcessor.getCurrentBorder();
            if (border != null && border.isKnown()) {
                int newBorderX = border.getHorizontalBorderIndex();
                int newBorderY = border.getVerticalBorderIndex();
                if (newBorderX != mLastCachedBorderX || newBorderY != mLastCachedBorderY) {
                    mBorderX = newBorderX;
                    mBorderY = newBorderY;
                    mLastCachedBorderX = newBorderX;
                    mLastCachedBorderY = newBorderY;
                }
            }
        }
    }
    
    private void sendPixelData(ByteBuffer buffer, int width, int height,
                               int rowStride, int pixelStride) {
        final int bx = mBorderX;
        final int by = mBorderY;
        final int effWidth = width - (bx << 1);
        final int effHeight = height - (by << 1);
        
        if (effWidth <= 0 || effHeight <= 0) return;
        
        byte[] rgb = getRgbBuffer(effWidth, effHeight);
        extractRgb(buffer, width, height, rowStride, pixelStride, bx, by, effWidth, effHeight, rgb);
        
        if (mAnimationSync != null) {
            rgb = mAnimationSync.applyAnimationToFrame(rgb);
        }
        
        mListener.sendFrame(rgb, effWidth, effHeight);
        
        mRgbBufferIndex = (mRgbBufferIndex + 1) % RGB_BUFFER_RING_SIZE;
    }
    
    private byte[] getRgbBuffer(int effWidth, int effHeight) {
        int requiredSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB;
        byte[] buffer = mRgbBufferRing[mRgbBufferIndex];
        
        if (buffer == null || buffer.length < requiredSize) {
            buffer = new byte[requiredSize];
            mRgbBufferRing[mRgbBufferIndex] = buffer;
        }
        
        return buffer;
    }
    
    private void extractRgb(ByteBuffer buffer, int width, int height,
                            int rowStride, int pixelStride,
                            int bx, int by, int effWidth, int effHeight, byte[] rgb) {
        final int endY = height - by;
        final int endX = width - bx;
        int rgbIdx = 0;
        
        if (pixelStride == BYTES_PER_PIXEL_RGBA && rowStride == width * BYTES_PER_PIXEL_RGBA) {
            final int rowBytes = effWidth * BYTES_PER_PIXEL_RGBA;
            
            if (mRowBuffer == null || mRowBuffer.length < rowBytes) {
                mRowBuffer = new byte[rowBytes];
            }
            
            final int savedPos = buffer.position();
            
            for (int y = by; y < endY; y++) {
                buffer.position(y * rowStride + bx * BYTES_PER_PIXEL_RGBA);
                buffer.get(mRowBuffer, 0, rowBytes);
                
                int i = 0;
                final int unrollLimit = rowBytes - 15;
                for (; i < unrollLimit; i += 16) {
                    rgb[rgbIdx++] = mRowBuffer[i];
                    rgb[rgbIdx++] = mRowBuffer[i + 1];
                    rgb[rgbIdx++] = mRowBuffer[i + 2];
                    rgb[rgbIdx++] = mRowBuffer[i + 4];
                    rgb[rgbIdx++] = mRowBuffer[i + 5];
                    rgb[rgbIdx++] = mRowBuffer[i + 6];
                    rgb[rgbIdx++] = mRowBuffer[i + 8];
                    rgb[rgbIdx++] = mRowBuffer[i + 9];
                    rgb[rgbIdx++] = mRowBuffer[i + 10];
                    rgb[rgbIdx++] = mRowBuffer[i + 12];
                    rgb[rgbIdx++] = mRowBuffer[i + 13];
                    rgb[rgbIdx++] = mRowBuffer[i + 14];
                }
                for (; i < rowBytes; i += BYTES_PER_PIXEL_RGBA) {
                    rgb[rgbIdx++] = mRowBuffer[i];
                    rgb[rgbIdx++] = mRowBuffer[i + 1];
                    rgb[rgbIdx++] = mRowBuffer[i + 2];
                }
            }
            
            buffer.position(savedPos);
        } else {
            for (int y = by; y < endY; y++) {
                final int rowOff = y * rowStride;
                for (int x = bx; x < endX; x++) {
                    final int off = rowOff + x * pixelStride;
                    rgb[rgbIdx++] = buffer.get(off);
                    rgb[rgbIdx++] = buffer.get(off + 1);
                    rgb[rgbIdx++] = buffer.get(off + 2);
                }
            }
        }
    }
    
    private void sendAverageColor(ByteBuffer buffer, int width, int height,
                                  int rowStride, int pixelStride) {
        final int bx = mBorderX;
        final int by = mBorderY;
        final int startX = bx;
        final int startY = by;
        final int endX = width - bx;
        final int endY = height - by;
        
        if (endX <= startX || endY <= startY) return;
        
        long r = 0, g = 0, b = 0;
        int count = 0;
        
        for (int y = startY; y < endY; y += 4) {
            final int rowOff = y * rowStride;
            for (int x = startX; x < endX; x += 4) {
                final int off = rowOff + x * pixelStride;
                r += buffer.get(off) & 0xFF;
                g += buffer.get(off + 1) & 0xFF;
                b += buffer.get(off + 2) & 0xFF;
                count++;
            }
        }
        
        if (count > 0) {
            mAvgColorResult[0] = (byte) (r / count);
            mAvgColorResult[1] = (byte) (g / count);
            mAvgColorResult[2] = (byte) (b / count);
            mListener.sendFrame(mAvgColorResult, 1, 1);
        }
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "Stopping");
        mRunning = false;
        setCapturing(false);
        
        if (mAudioVisualizer != null) {
            mAudioVisualizer.stop();
            mAudioVisualizer.release();
            mAudioVisualizer = null;
        }
        
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
        
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        
        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread = null;
            mCaptureHandler = null;
        }
        
        mRgbBufferRing = new byte[RGB_BUFFER_RING_SIZE][];
        mRgbBufferIndex = 0;
        mRowBuffer = null;
        mBorderX = 0;
        mBorderY = 0;
        mFrameCount = 0;
        mAnimationSync = null;
        mPreferences = null;

        clearAndDisconnect();
        
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    @Override
    public void pauseRecording() {
        if (DEBUG) Log.i(TAG, "Pausing");
        mRunning = false;
        setCapturing(false);
        if (mAudioVisualizer != null) {
            mAudioVisualizer.stop();
        }
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "Resuming");
        if (!isCapturing() && mImageReader != null) {
            startCapture();
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void setOrientation(int orientation) {
        if (mVirtualDisplay == null || orientation == mCurrentOrientation) return;
        
        mCurrentOrientation = orientation;
        mRunning = false;
        setCapturing(false);
        
        final int tmp = mCaptureWidth;
        mCaptureWidth = mCaptureHeight;
        mCaptureHeight = tmp;
        
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
        
        mVirtualDisplay.resize(mCaptureWidth, mCaptureHeight, mDensity);
        
        if (mImageReader != null) {
            mImageReader.close();
        }
        
        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);
        
        mVirtualDisplay.setSurface(mImageReader.getSurface());
        
        mRgbBufferRing = new byte[RGB_BUFFER_RING_SIZE][];
        mRgbBufferIndex = 0;
        mRowBuffer = null;
        
        startCapture();
    }

    @Override
    public void clearLights() {
        super.clearLights();
    }
}
