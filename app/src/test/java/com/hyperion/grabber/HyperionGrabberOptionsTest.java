package com.hyperion.grabber;

import com.hyperion.grabber.common.util.HyperionGrabberOptions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HyperionGrabberOptionsTest {

    @Test
    public void findDivisor_producesAtLeastMinimumPacketSize() {
        HyperionGrabberOptions options = new HyperionGrabberOptions(60, 34, 30, false);
        int divisor = options.findDivisor(1920, 1080);

        assertTrue("divisor must be >= 1", divisor >= 1);

        int packetSize = (1920 / divisor) * (1080 / divisor) * 3;
        int minimum = 60 * 34 * 3;
        assertTrue("scaled packet size " + packetSize + " must be >= minimum " + minimum,
                packetSize >= minimum);
    }

    @Test
    public void findDivisor_smallScreenStillWorks() {
        HyperionGrabberOptions options = new HyperionGrabberOptions(4, 4, 30, false);
        int divisor = options.findDivisor(1280, 720);

        assertTrue("divisor must be >= 1", divisor >= 1);

        int packetSize = (1280 / divisor) * (720 / divisor) * 3;
        assertTrue(packetSize >= 4 * 4 * 3);
    }

    @Test
    public void findDivisor_tinyLedCountNeverReturnsZero() {
        HyperionGrabberOptions options = new HyperionGrabberOptions(1, 1, 30, false);
        assertTrue(options.findDivisor(10, 10) >= 1);
    }

    @Test
    public void frameRate_invalidValueIsClamped() {
        HyperionGrabberOptions zero = new HyperionGrabberOptions(60, 34, 0, false);
        assertTrue("frame rate must be clamped to at least 1", zero.getFrameRate() >= 1);

        HyperionGrabberOptions negative = new HyperionGrabberOptions(60, 34, -5, false);
        assertTrue("frame rate must be clamped to at least 1", negative.getFrameRate() >= 1);
    }

    @Test
    public void captureResolution_tiersScaleMaxDimension() {
        HyperionGrabberOptions low = new HyperionGrabberOptions(60, 34, 30, false, 0);
        HyperionGrabberOptions medium = new HyperionGrabberOptions(60, 34, 30, false, 1);
        HyperionGrabberOptions high = new HyperionGrabberOptions(60, 34, 30, false, 2);

        assertTrue("codec: low < medium", low.getCodecMaxDimension() < medium.getCodecMaxDimension());
        assertTrue("codec: medium < high", medium.getCodecMaxDimension() < high.getCodecMaxDimension());
        assertTrue("imagereader: low < medium", low.getImageReaderMaxDimension() < medium.getImageReaderMaxDimension());
        assertTrue("imagereader: medium < high", medium.getImageReaderMaxDimension() < high.getImageReaderMaxDimension());
    }

    @Test
    public void captureResolution_outOfRangeIsClamped() {
        HyperionGrabberOptions negative = new HyperionGrabberOptions(60, 34, 30, false, -1);
        HyperionGrabberOptions huge = new HyperionGrabberOptions(60, 34, 30, false, 99);
        HyperionGrabberOptions low = new HyperionGrabberOptions(60, 34, 30, false, 0);
        HyperionGrabberOptions high = new HyperionGrabberOptions(60, 34, 30, false, 2);

        // Below-range clamps to the smallest tier, above-range to the largest
        assertEquals(low.getCodecMaxDimension(), negative.getCodecMaxDimension());
        assertEquals(high.getCodecMaxDimension(), huge.getCodecMaxDimension());
        assertTrue("clamped values must be valid", negative.getImageReaderMaxDimension() > 0);
    }
}
