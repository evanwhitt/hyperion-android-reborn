package com.hyperion.grabber;

import com.hyperion.grabber.common.network.HyperionFlatBuffers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HyperionFlatBuffersTest {

    @Test
    public void toHyperionRgb_stripsAlphaByte() {
        // Android Color.BLACK == 0xFF000000 -> must become 0x000000, not red
        assertEquals(0x000000, HyperionFlatBuffers.toHyperionRgb(0xFF000000));
        // Color.BLUE == 0xFF0000FF -> must stay blue, not become red
        assertEquals(0x0000FF, HyperionFlatBuffers.toHyperionRgb(0xFF0000FF));
        // Color.RED == 0xFFFF0000 -> stays red
        assertEquals(0xFF0000, HyperionFlatBuffers.toHyperionRgb(0xFFFF0000));
        // Color.MAGENTA == 0xFFFF00FF -> stays magenta
        assertEquals(0xFF00FF, HyperionFlatBuffers.toHyperionRgb(0xFFFF00FF));
        // Values with no alpha already are unchanged
        assertEquals(0x00FF00, HyperionFlatBuffers.toHyperionRgb(0x00FF00));
    }
}
