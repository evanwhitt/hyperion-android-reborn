package com.hyperion.grabber;

import com.hyperion.grabber.common.util.HdrConverter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HdrConverterTest {

    @Test
    public void recognizesPqAndHlg() {
        assertTrue(HdrConverter.isHdrTransfer(HdrConverter.COLOR_TRANSFER_PQ));
        assertTrue(HdrConverter.isHdrTransfer(HdrConverter.COLOR_TRANSFER_HLG));
        assertFalse(HdrConverter.isHdrTransfer(3));
        assertFalse(HdrConverter.isHdrTransfer(-1));
    }

    @Test
    public void pqLutEndpointsAndMonotonic() {
        byte[] lut = HdrConverter.buildLut(HdrConverter.COLOR_TRANSFER_PQ);
        assertEquals(0, lut[0]);
        assertEquals(-1, lut[255]);
        for (int i = 1; i < 256; i++) {
            assertTrue("pq lut not monotonic at " + i,
                    (lut[i] & 0xFF) >= (lut[i - 1] & 0xFF));
        }
    }

    @Test
    public void pqLutLiftsMidtones() {
        byte[] lut = HdrConverter.buildLut(HdrConverter.COLOR_TRANSFER_PQ);
        assertTrue("pq mid-tone should be visible, was " + (lut[128] & 0xFF),
                (lut[128] & 0xFF) > 100);
    }

    @Test
    public void hlgLutEndpointsAndMonotonic() {
        byte[] lut = HdrConverter.buildLut(HdrConverter.COLOR_TRANSFER_HLG);
        assertEquals(0, lut[0]);
        assertEquals(-1, lut[255]);
        for (int i = 1; i < 256; i++) {
            assertTrue("hlg lut not monotonic at " + i,
                    (lut[i] & 0xFF) >= (lut[i - 1] & 0xFF));
        }
    }

    @Test
    public void hlgEotfKnownValues() {
        assertEquals(0f, HdrConverter.hlgEotf(0f), 1e-6f);
        assertEquals(0.0833333f, HdrConverter.hlgEotf(0.5f), 1e-5f);
        assertTrue(HdrConverter.hlgEotf(1f) > 0.99f);
    }

    @Test
    public void srgbEncodeKnownValues() {
        assertEquals(0f, HdrConverter.srgbEncode(0f), 1e-6f);
        assertEquals(1f, HdrConverter.srgbEncode(1f), 1e-4f);
        assertEquals(12.92f * 0.001f, HdrConverter.srgbEncode(0.001f), 1e-4f);
    }
}
