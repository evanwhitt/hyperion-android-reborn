package com.hyperion.grabber;

import com.hyperion.grabber.common.util.BorderProcessor;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BorderProcessorTest {

    private static final int WIDTH = 100;
    private static final int HEIGHT = 100;
    private static final int ROW_STRIDE = WIDTH * 4;
    private static final int PIXEL_STRIDE = 4;

    @Test
    public void solidImage_hasNoBorder() {
        BorderProcessor processor = new BorderProcessor(5);

        // Fully black image (simulates a letterboxed/blank screen)
        ByteBuffer buffer = ByteBuffer.allocate(ROW_STRIDE * HEIGHT);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put((byte) 0);
        }
        buffer.rewind();

        for (int i = 0; i < 100; i++) {
            processor.parseBorder(buffer, WIDTH, HEIGHT, ROW_STRIDE, PIXEL_STRIDE);
        }

        BorderProcessor.BorderObject border = processor.getCurrentBorder();
        // An all-black screen never establishes a border: current border stays null
        // (or, if it were ever set, must not be "known").
        assertTrue("all-black image must not have a known border",
                border == null || !border.isKnown());
    }

    @Test
    public void contentWithBlackFrame_detectsBorderIndex() {
        BorderProcessor processor = new BorderProcessor(5);

        // 10 px black frame around white content
        ByteBuffer buffer = ByteBuffer.allocate(ROW_STRIDE * HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                boolean inside = x >= 10 && x < 90 && y >= 10 && y < 90;
                int offset = y * ROW_STRIDE + x * PIXEL_STRIDE;
                if (inside) {
                    buffer.put(offset, (byte) 255);
                    buffer.put(offset + 1, (byte) 255);
                    buffer.put(offset + 2, (byte) 255);
                    buffer.put(offset + 3, (byte) 255);
                } else {
                    buffer.put(offset, (byte) 0);
                    buffer.put(offset + 1, (byte) 0);
                    buffer.put(offset + 2, (byte) 0);
                    buffer.put(offset + 3, (byte) 255);
                }
            }
        }

        for (int i = 0; i < 100; i++) {
            processor.parseBorder(buffer, WIDTH, HEIGHT, ROW_STRIDE, PIXEL_STRIDE);
        }

        BorderProcessor.BorderObject border = processor.getCurrentBorder();
        assertNotNull(border);
        assertTrue("expected a known border", border.isKnown());
        assertEquals("horizontal (x) border index", 10, border.getHorizontalBorderIndex());
        assertEquals("vertical (y) border index", 10, border.getVerticalBorderIndex());
    }
}
