package com.hyperion.grabber;

import com.hyperion.grabber.common.util.AdaptiveFrameRate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdaptiveFrameRateTest {

    @Test
    public void baseIntervalFor30fps() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        assertEquals(33L, fps.baseIntervalMs());
    }

    @Test
    public void fastCaptureKeepsBaseInterval() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long interval = 33L * 1_000_000L; // exactly the deadline
        long delay = fps.update(interval / 2);
        assertEquals(33L, delay);
    }

    @Test
    public void sustainedSlowCaptureBacksOff() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long slow = 40L * 1_000_000L; // exceeds 85% of 33ms
        long first = 0;
        for (int i = 0; i < 20; i++) {
            first = fps.update(slow);
        }
        assertTrue(first > 33L);
    }

    @Test
    public void backsOffCappedAtFiveFps() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long slow = 60L * 1_000_000L;
        long last = 0;
        for (int i = 0; i < 100; i++) {
            last = fps.update(slow);
        }
        assertTrue(last <= 200L); // 1000ms / 5fps
    }

    @Test
    public void loadCountTracksHighLoad() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long slow = 40L * 1_000_000L;
        assertEquals(0, fps.highLoadCount());
        fps.update(slow);
        assertEquals(1, fps.highLoadCount());
        fps.update(slow);
        fps.update(slow);
        fps.update(slow);
        assertTrue(fps.underHighLoad());
    }

    @Test
    public void recoveryReturnsTowardBase() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long slow = 40L * 1_000_000L;
        long fast = 5L * 1_000_000L;
        for (int i = 0; i < 20; i++) {
            fps.update(slow);
        }
        long backedOff = fps.update(slow);
        assertTrue(backedOff > 33L);
        long recovered = 0;
        for (int i = 0; i < 200; i++) {
            recovered = fps.update(fast);
        }
        assertEquals(33L, recovered);
    }

    @Test
    public void resetClearsBackoff() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long slow = 40L * 1_000_000L;
        for (int i = 0; i < 20; i++) {
            fps.update(slow);
        }
        fps.reset();
        assertEquals(0, fps.highLoadCount());
        assertEquals(33L, fps.update(5L * 1_000_000L));
    }

    @Test
    public void maxedOutAfterSustainedLoad() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long slow = 40L * 1_000_000L;
        assertFalse(fps.isMaxedOut());
        for (int i = 0; i < 40; i++) {
            fps.update(slow);
        }
        assertTrue(fps.isMaxedOut());
    }

    @Test
    public void notMaxedOutWhenFast() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(30);
        long fast = 5L * 1_000_000L;
        for (int i = 0; i < 40; i++) {
            fps.update(fast);
        }
        assertFalse(fps.isMaxedOut());
    }

    @Test
    public void lowFrameRateHasNoRoomToBackOff() {
        AdaptiveFrameRate fps = new AdaptiveFrameRate(5);
        long slow = 250L * 1_000_000L;
        long last = 0;
        for (int i = 0; i < 50; i++) {
            last = fps.update(slow);
        }
        assertEquals(200L, last); // floor == base
    }
}
