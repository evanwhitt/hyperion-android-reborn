package com.hyperion.grabber.common;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;
import com.hyperion.grabber.common.util.AdaptiveFrameRate;

import java.nio.ByteBuffer;

/**
 * Alternative screen capture pipeline for devices where a VirtualDisplay feeding an
 * ImageReader directly returns black frames (seen on Amlogic, TCL and several other TV SoCs).
 *
 * <p>Pipeline (the same trick scrcpy uses):
 * <pre>
 *   MediaProjection -> VirtualDisplay -> MediaCodec encoder (surface input)
 *     -> H.264 -> MediaCodec decoder -> YUV420 Image -> RGB -> downscale -> sendFrame
 * </pre>
 *
 * <p>Because encoding/decoding is done by the hardware, this path is often lower CPU as well.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class HyperionCodecScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionCodecEncoder";
    private static final boolean DEBUG = false;

    private static final int BIT_RATE = 2_000_000;

    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private MediaCodec mEncoder;
    private MediaCodec mDecoder;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private volatile boolean mRunning;
    private volatile boolean mDecoderStarted;

    private final MediaCodec.BufferInfo mEncoderInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo mDecoderInfo = new MediaCodec.BufferInfo();

    private int mCaptureWidth;
    private int mCaptureHeight;
    private int mOutWidth;
    private int mOutHeight;
    private final AdaptiveFrameRate mAdaptiveFps;
    private final int mMaxCaptureDimension;
    private byte[] mRgbBuffer;
    private final byte[] mAvgColorResult = new byte[3];

    private static final long WHITE_FRAME_THRESHOLD = 60; // ~2s at 30fps
    private static final int WHITE_Y_THRESHOLD = 230;
    private static final double WHITE_FILL_RATIO = 0.90;
    private long mWhiteFrameCount;
    private boolean mWhiteFallbackTriggered;

    // Letterbox auto-crop (credit: robin - gitea.datadrake.cloud/robin/hyperion-android-reborn-edited)
    private static final int CONTENT_BOUNDS_LOCK_STABLE = 2;
    private static final int CONTENT_BOUNDS_HYSTERESIS_PX = 12;
    private static final int BLACK_Y_THRESHOLD = 32;
    private static final float LETTERBOX_FILL_MIN = 0.05f;
    private static final float LETTERBOX_FILL_MAX = 0.70f;

    private final Rect mContentBounds = new Rect();
    private final Rect mPendingBounds = new Rect();
    private boolean mContentBoundsValid;
    private boolean mContentBoundsLocked;
    private int mStableBoundsCount;

    private final VirtualDisplay.Callback mDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            if (DEBUG) Log.d(TAG, "Display paused");
        }

        @Override
        public void onResumed() {
            if (DEBUG) Log.d(TAG, "Display resumed");
        }

        @Override
        public void onStopped() {
            if (DEBUG) Log.d(TAG, "Display stopped");
            mRunning = false;
            setCapturing(false);
        }
    };

    public HyperionCodecScreenEncoder(HyperionThread.HyperionThreadListener listener,
                                      MediaProjection projection,
                                      int screenWidth, int screenHeight,
                                      int density,
                                      HyperionGrabberOptions options,
                                      Context context) {
        super(listener, projection, screenWidth, screenHeight, density, options);
        mAdaptiveFps = new AdaptiveFrameRate(mFrameRate);
        mMaxCaptureDimension = options.getCodecMaxDimension();
        computeCaptureSize(screenWidth, screenHeight);
        mOutWidth = getGrabberWidth();
        mOutHeight = getGrabberHeight();
        mRgbBuffer = new byte[mOutWidth * mOutHeight * 3];
        init();
    }

    /** Keeps the capture size at the display's aspect ratio so the encoder frame isn't letterboxed. */
    private void computeCaptureSize(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            mCaptureWidth = mMaxCaptureDimension;
            mCaptureHeight = Math.max(16, mMaxCaptureDimension * 9 / 16);
            return;
        }
        float maxHeight = Math.max(16, mMaxCaptureDimension * (float) screenHeight / Math.max(1, screenWidth));
        float scale = Math.min(
                (float) mMaxCaptureDimension / screenWidth,
                maxHeight / screenHeight);
        mCaptureWidth = Math.max(16, Math.round(screenWidth * scale)) & ~1;
        mCaptureHeight = Math.max(16, Math.round(screenHeight * scale)) & ~1;
    }

    private void init() {
        mCaptureThread = new HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
            }
        }, mHandler);

        startPipeline();
    }

    private void startPipeline() {
        mContentBoundsLocked = false;
        mContentBoundsValid = false;
        mStableBoundsCount = 0;
        try {
            mEncoder = createEncoder();
            mInputSurface = mEncoder.createInputSurface();

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    TAG,
                    mCaptureWidth, mCaptureHeight, mDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    mInputSurface,
                    mDisplayCallback,
                    mHandler);

            mEncoder.start();
            mDecoderStarted = false;
            startCapture();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start codec pipeline", e);
            mRunning = false;
            setCapturing(false);
            releaseResources();
        }
    }

    private MediaCodec createEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mCaptureWidth, mCaptureHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return encoder;
    }

    /** Lazily creates the H.264 decoder once the encoder reports its output format (with csd data). */
    private void ensureDecoder(MediaFormat encoderFormat) {
        if (mDecoderStarted) return;
        try {
            int width = encoderFormat.containsKey(MediaFormat.KEY_WIDTH) ? encoderFormat.getInteger(MediaFormat.KEY_WIDTH) : mCaptureWidth;
            int height = encoderFormat.containsKey(MediaFormat.KEY_HEIGHT) ? encoderFormat.getInteger(MediaFormat.KEY_HEIGHT) : mCaptureHeight;

            MediaFormat decFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            decFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            decFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
            ByteBuffer csd0 = encoderFormat.getByteBuffer("csd-0");
            if (csd0 != null) decFormat.setByteBuffer("csd-0", csd0);
            if (encoderFormat.containsKey("csd-1")) {
                decFormat.setByteBuffer("csd-1", encoderFormat.getByteBuffer("csd-1"));
            }

            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mDecoder.configure(decFormat, null, null, 0);
            mDecoder.start();
            mDecoderStarted = true;
            if (DEBUG) Log.d(TAG, "Decoder started " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start decoder", e);
            mDecoderStarted = false;
        }
    }

    private void startCapture() {
        mRunning = true;
        setCapturing(true);
        mCaptureHandler.removeCallbacksAndMessages(null);
        mCaptureHandler.post(mCaptureRunnable);
    }

    private final Runnable mCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || mCaptureHandler == null) return;
            final long stepStart = System.nanoTime();
            try {
                stepCodec();
            } catch (Exception e) {
                if (DEBUG) Log.w(TAG, "Codec step error: " + e.getMessage(), e);
            }
            if (mRunning && mCaptureHandler != null) {
                mCaptureHandler.postDelayed(this,
                        Math.max(1L, mAdaptiveFps.update(System.nanoTime() - stepStart)));
            }
        }
    };

    private void stepCodec() {
        drainEncoder();
        drainDecoder();
    }

    private void drainEncoder() {
        if (mEncoder == null) return;
        while (true) {
            int index = mEncoder.dequeueOutputBuffer(mEncoderInfo, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ensureDecoder(mEncoder.getOutputFormat());
            } else if (index >= 0) {
                boolean codecConfig = (mEncoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean endOfStream = (mEncoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (mDecoderStarted && !codecConfig && mEncoderInfo.size > 0) {
                    feedDecoder(index);
                }
                mEncoder.releaseOutputBuffer(index, false);
                if (endOfStream) break;
            }
        }
    }

    private void feedDecoder(int encoderIndex) {
        if (mDecoder == null) return;
        int inputIndex = mDecoder.dequeueInputBuffer(0);
        if (inputIndex < 0) {
            // No room in the decoder yet; the caller releases the encoder buffer.
            return;
        }
        ByteBuffer encData = mEncoder.getOutputBuffer(encoderIndex);
        ByteBuffer decInput = mDecoder.getInputBuffer(inputIndex);
        if (encData != null && decInput != null) {
            decInput.clear();
            if (mEncoderInfo.size > 0) {
                encData.position(mEncoderInfo.offset);
                encData.limit(mEncoderInfo.offset + mEncoderInfo.size);
                decInput.put(encData);
            }
            mDecoder.queueInputBuffer(inputIndex, 0, mEncoderInfo.size,
                    mEncoderInfo.presentationTimeUs, mEncoderInfo.flags);
        } else {
            mDecoder.queueInputBuffer(inputIndex, 0, 0, mEncoderInfo.presentationTimeUs, 0);
        }
    }

    private void drainDecoder() {
        if (mDecoder == null || !mDecoderStarted) return;
        while (true) {
            int index = mDecoder.dequeueOutputBuffer(mDecoderInfo, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (DEBUG) Log.d(TAG, "Decoder output format: " + mDecoder.getOutputFormat());
            } else if (index >= 0) {
                if (mDecoderInfo.size > 0) {
                    sendDecodedFrame(index);
                }
                mDecoder.releaseOutputBuffer(index, false);
                if ((mDecoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void sendDecodedFrame(int index) {
        Image image = null;
        try {
            image = mDecoder.getOutputImage(index);
            if (image != null) {
                if (!mWhiteFallbackTriggered && isFrameWhiteYuv(image)) {
                    mWhiteFrameCount++;
                    if (mWhiteFrameCount >= WHITE_FRAME_THRESHOLD) {
                        mWhiteFallbackTriggered = true;
                        if (mWhiteFrameCallback != null) {
                            mWhiteFrameCallback.run();
                        }
                    }
                } else {
                    mWhiteFrameCount = 0;
                }
                convertYuvToRgb(image, mRgbBuffer, mOutWidth, mOutHeight);
                if (mAvgColor) {
                    sendAverageColor();
                } else {
                    mListener.sendFrame(mRgbBuffer, mOutWidth, mOutHeight);
                }
            }
        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "Error converting frame: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    private boolean isFrameWhiteYuv(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuf = yPlane.getBuffer();
        Rect crop = image.getCropRect();
        int cropLeft = Math.max(0, crop.left);
        int cropTop = Math.max(0, crop.top);
        int cropW = Math.min(image.getWidth() - cropLeft, crop.width());
        int cropH = Math.min(image.getHeight() - cropTop, crop.height());
        return isFrameWhiteYuv(yBuf, yPlane.getRowStride(), yPlane.getPixelStride(),
                cropLeft, cropTop, cropW, cropH);
    }

    private boolean isFrameWhiteYuv(ByteBuffer yBuf, int yRowStride, int yPixelStride,
                                    int cropLeft, int cropTop, int cropW, int cropH) {
        if (cropW <= 0 || cropH <= 0 || yBuf == null) return false;
        final int base = yBuf.position();
        final int step = Math.max(4, Math.max(cropW, cropH) / 24);
        int bright = 0;
        int total = 0;
        for (int y = cropTop; y < cropTop + cropH; y += step) {
            final int row = base + y * yRowStride;
            for (int x = cropLeft; x < cropLeft + cropW; x += step) {
                if ((yBuf.get(row + x * yPixelStride) & 0xFF) >= WHITE_Y_THRESHOLD) bright++;
                total++;
            }
        }
        if (total == 0) return false;
        return (bright / (double) total) >= WHITE_FILL_RATIO;
    }

    private void sendAverageColor() {
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int i = 0; i < mRgbBuffer.length; i += 3) {
            r += mRgbBuffer[i] & 0xFF;
            g += mRgbBuffer[i + 1] & 0xFF;
            b += mRgbBuffer[i + 2] & 0xFF;
            count++;
        }
        if (count > 0) {
            mAvgColorResult[0] = (byte) (r / count);
            mAvgColorResult[1] = (byte) (g / count);
            mAvgColorResult[2] = (byte) (b / count);
            mListener.sendFrame(mAvgColorResult, 1, 1);
        }
    }

    /** YUV420 (I420 / NV12 / flexible) to RGB point-sampled downscale. */
    private void convertYuvToRgb(Image image, byte[] rgb, int outWidth, int outHeight) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 2) return;

        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes.length >= 3 ? planes[2] : planes[1];

        ByteBuffer yBuf = yPlane.getBuffer();
        ByteBuffer uBuf = uPlane.getBuffer();
        ByteBuffer vBuf = vPlane.getBuffer();

        final int yRowStride = yPlane.getRowStride();
        final int yPixelStride = yPlane.getPixelStride();
        final int uRowStride = uPlane.getRowStride();
        final int uPixelStride = uPlane.getPixelStride();
        final int vRowStride = vPlane.getRowStride();
        final int vPixelStride = vPlane.getPixelStride();
        final boolean semiPlanar = planes.length < 3;

        Rect crop = image.getCropRect();
        final int cropLeft = Math.max(0, crop.left);
        final int cropTop = Math.max(0, crop.top);
        final int cropW = Math.min(image.getWidth() - cropLeft, crop.width());
        final int cropH = Math.min(image.getHeight() - cropTop, crop.height());
        if (cropW <= 0 || cropH <= 0) return;

        // Detect the letterboxed active picture (once) and crop to it.
        if (!mContentBoundsLocked) {
            updateContentBounds(yBuf, yRowStride, yPixelStride, crop);
        }
        if (!mContentBoundsValid) {
            mContentBounds.set(crop);
            mContentBoundsValid = true;
        }

        final int srcLeft = Math.max(cropLeft, mContentBounds.left);
        final int srcTop = Math.max(cropTop, mContentBounds.top);
        final int srcRight = Math.min(cropLeft + cropW, mContentBounds.right);
        final int srcBottom = Math.min(cropTop + cropH, mContentBounds.bottom);
        final int srcW = Math.max(2, srcRight - srcLeft);
        final int srcH = Math.max(2, srcBottom - srcTop);

        int rgbIdx = 0;
        for (int y = 0; y < outHeight; y++) {
            int srcY = srcTop + (y * srcH) / outHeight;
            for (int x = 0; x < outWidth; x++) {
                int srcX = srcLeft + (x * srcW) / outWidth;

                int yOff = yBuf.position() + srcY * yRowStride + srcX * yPixelStride;
                int chromaX = srcX / 2;
                int chromaY = srcY / 2;
                int uOff = uBuf.position() + chromaY * uRowStride + chromaX * uPixelStride;
                int vOff = semiPlanar ? uOff + 1 : vBuf.position() + chromaY * vRowStride + chromaX * vPixelStride;

                int Y = (yBuf.get(yOff) & 0xFF) - 16;
                int U = (uBuf.get(uOff) & 0xFF) - 128;
                int V = (vBuf.get(vOff) & 0xFF) - 128;

                int r = (int) (1.164f * Y + 1.596f * V);
                int g = (int) (1.164f * Y - 0.392f * U - 0.813f * V);
                int b = (int) (1.164f * Y + 2.017f * U);

                rgb[rgbIdx++] = (byte) clamp(r);
                rgb[rgbIdx++] = (byte) clamp(g);
                rgb[rgbIdx++] = (byte) clamp(b);
            }
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private void updateContentBounds(ByteBuffer yBuf, int yRowStride, int yPixelStride, Rect crop) {
        final Rect detected = detectLetterboxByEdges(yBuf, yRowStride, yPixelStride, crop);
        final float fill = (detected.width() * (float) detected.height())
                / Math.max(1, crop.width() * crop.height());

        if (fill > LETTERBOX_FILL_MIN && fill <= LETTERBOX_FILL_MAX) {
            mContentBounds.set(detected);
            mContentBoundsValid = true;
            mContentBoundsLocked = true;
            return;
        }

        if (!mContentBoundsValid) {
            mContentBounds.set(crop);
            mPendingBounds.set(detected);
            mContentBoundsValid = true;
            mStableBoundsCount = 1;
            return;
        }

        if (boundsSimilar(mPendingBounds, detected, CONTENT_BOUNDS_HYSTERESIS_PX)) {
            mStableBoundsCount++;
            if (mStableBoundsCount >= CONTENT_BOUNDS_LOCK_STABLE) {
                mContentBounds.set(detected);
                mContentBoundsLocked = true;
            }
        } else {
            mPendingBounds.set(detected);
            mStableBoundsCount = 1;
        }
    }

    private Rect detectLetterboxByEdges(ByteBuffer yBuf, int yRowStride, int yPixelStride, Rect crop) {
        final int threshold = BLACK_Y_THRESHOLD;
        final int minBright = Math.max(3, Math.min(crop.width(), crop.height()) / 64);
        final int step = 8;
        final int base = yBuf.position();

        int top = crop.top;
        for (; top < crop.bottom - 2; top += step) {
            if (countBrightInRow(base, yBuf, yRowStride, yPixelStride, crop.left, crop.right, top, threshold)
                    >= minBright) break;
        }

        int bottom = crop.bottom - 1;
        for (; bottom > top + 2; bottom -= step) {
            if (countBrightInRow(base, yBuf, yRowStride, yPixelStride, crop.left, crop.right, bottom, threshold)
                    >= minBright) break;
        }

        int left = crop.left;
        for (; left < crop.right - 2; left += step) {
            if (countBrightInCol(base, yBuf, yRowStride, yPixelStride, left, top, bottom, threshold)
                    >= minBright) break;
        }

        int right = crop.right - 1;
        for (; right > left + 2; right -= step) {
            if (countBrightInCol(base, yBuf, yRowStride, yPixelStride, right, top, bottom, threshold)
                    >= minBright) break;
        }

        if (right - left < 8 || bottom - top < 8) {
            return new Rect(crop);
        }

        left = Math.max(crop.left, left) & ~1;
        top = Math.max(crop.top, top) & ~1;
        right = Math.min(crop.right, right + 1);
        bottom = Math.min(crop.bottom, bottom + 1);
        if (((right - left) & 1) != 0) right = Math.min(crop.right, right + 1);
        if (((bottom - top) & 1) != 0) bottom = Math.min(crop.bottom, bottom + 1);

        return new Rect(left, top, right, bottom);
    }

    private static int countBrightInRow(int base, ByteBuffer yBuf, int yRowStride, int yPixelStride,
                                        int x0, int x1, int y, int threshold) {
        int count = 0;
        final int row = base + y * yRowStride;
        for (int x = x0; x < x1; x += 4) {
            if ((yBuf.get(row + x * yPixelStride) & 0xFF) > threshold) count++;
        }
        return count;
    }

    private static int countBrightInCol(int base, ByteBuffer yBuf, int yRowStride, int yPixelStride,
                                        int x, int y0, int y1, int threshold) {
        int count = 0;
        final int xOff = base + x * yPixelStride;
        for (int y = y0; y <= y1; y += 4) {
            if ((yBuf.get(y * yRowStride + xOff) & 0xFF) > threshold) count++;
        }
        return count;
    }

    private static boolean boundsSimilar(Rect a, Rect b, int tolerancePx) {
        return Math.abs(a.left - b.left) <= tolerancePx
                && Math.abs(a.top - b.top) <= tolerancePx
                && Math.abs(a.right - b.right) <= tolerancePx
                && Math.abs(a.bottom - b.bottom) <= tolerancePx;
    }

    private void requestSyncFrame() {
        if (mEncoder == null) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mEncoder.setParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "Failed to request sync frame", e);
        }
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "Stopping");
        mRunning = false;
        setCapturing(false);

        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }

        releaseResources();

        clearAndDisconnect();
    }

    private void releaseResources() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mEncoder != null) {
            try {
                mEncoder.stop();
            } catch (Exception ignored) {
            }
            mEncoder.release();
            mEncoder = null;
        }
        if (mDecoder != null) {
            try {
                mDecoder.stop();
            } catch (Exception ignored) {
            }
            mDecoder.release();
            mDecoder = null;
        }
        mDecoderStarted = false;
        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread = null;
            mCaptureHandler = null;
        }
    }

    @Override
    public void pauseRecording() {
        if (DEBUG) Log.i(TAG, "Pausing");
        mRunning = false;
        setCapturing(false);
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "Resuming");
        if (!isCapturing() && mEncoder != null) {
            requestSyncFrame();
            startCapture();
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void setOrientation(int orientation) {
        if (orientation == mCurrentOrientation) return;
        mCurrentOrientation = orientation;

        // Full pipeline teardown & rebuild with swapped capture dimensions
        mRunning = false;
        setCapturing(false);
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }

        final int tmp = mCaptureWidth;
        mCaptureWidth = mCaptureHeight;
        mCaptureHeight = tmp;

        releaseResources();
        startPipeline();
    }
}
