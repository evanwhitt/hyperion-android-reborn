package com.hyperion.grabber.common.util;

public final class AdaptiveFrameRate {
    private static final int HIGH_LOAD_THRESHOLD = 3;
    private static final int MAX_LOAD_COUNT = 5;
    private static final int MAX_STEPS = 8;
    private static final long STEP_MS = 16;
    private static final double DEADLINE_MISS_RATIO = 0.85;
    private static final int FPS_FLOOR = 5;

    private final long mBaseIntervalMs;
    private final long mMaxIntervalMs;
    private int mHighLoadCount;
    private int mStep;

    public AdaptiveFrameRate(int frameRate) {
        mBaseIntervalMs = 1000L / Math.max(1, frameRate);
        mMaxIntervalMs = 1000L / FPS_FLOOR;
    }

    public long baseIntervalMs() {
        return mBaseIntervalMs;
    }

    public int highLoadCount() {
        return mHighLoadCount;
    }

    public boolean underHighLoad() {
        return mHighLoadCount > HIGH_LOAD_THRESHOLD;
    }

    public boolean isMaxedOut() {
        return mStep >= MAX_STEPS;
    }

    public long update(long captureTimeNs) {
        if (captureTimeNs > (long) (mBaseIntervalMs * 1_000_000L * DEADLINE_MISS_RATIO)) {
            mHighLoadCount = Math.min(MAX_LOAD_COUNT, mHighLoadCount + 1);
        } else {
            mHighLoadCount = Math.max(0, mHighLoadCount - 1);
        }
        if (mHighLoadCount >= HIGH_LOAD_THRESHOLD) {
            mStep = Math.min(MAX_STEPS, mStep + 1);
        } else if (mHighLoadCount == 0) {
            mStep = Math.max(0, mStep - 1);
        }
        return Math.min(mBaseIntervalMs + mStep * STEP_MS, mMaxIntervalMs);
    }

    public void reset() {
        mHighLoadCount = 0;
        mStep = 0;
    }
}
