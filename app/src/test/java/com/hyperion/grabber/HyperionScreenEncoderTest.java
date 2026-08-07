package com.hyperion.grabber;

import com.hyperion.grabber.common.HyperionScreenEncoder;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HyperionScreenEncoderTest {

    private static final int W = 64;
    private static final int H = 36;
    private static final int ROW = W * 4;

    @Test
    public void isFrameBlack_allBlackFrame() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        assertTrue(HyperionScreenEncoder.isFrameBlack(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameBlack_brightFrameIsNotBlack() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        for (int i = 0; i < buf.capacity(); i++) {
            buf.put(i, (byte) 255);
        }
        assertFalse(HyperionScreenEncoder.isFrameBlack(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameBlack_handlesRowStridePadding() {
        // rowStride larger than width*4 simulates padded buffers
        int paddedRow = W * 4 + 16;
        ByteBuffer buf = ByteBuffer.allocate(paddedRow * H);
        assertTrue(HyperionScreenEncoder.isFrameBlack(buf, W, H, paddedRow, 4));

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int off = y * paddedRow + x * 4;
                buf.put(off, (byte) 200);
                buf.put(off + 1, (byte) 200);
                buf.put(off + 2, (byte) 200);
            }
        }
        assertFalse(HyperionScreenEncoder.isFrameBlack(buf, W, H, paddedRow, 4));
    }

    @Test
    public void isFrameBlack_invalidInputsReturnFalse() {
        assertFalse(HyperionScreenEncoder.isFrameBlack(null, W, H, ROW, 4));
        assertFalse(HyperionScreenEncoder.isFrameBlack(ByteBuffer.allocate(100), 0, H, ROW, 4));
        assertFalse(HyperionScreenEncoder.isFrameBlack(ByteBuffer.allocate(100), W, 0, ROW, 4));
    }

    @Test
    public void isFrameWhite_allWhiteFrame() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        for (int i = 0; i < buf.capacity(); i++) {
            buf.put(i, (byte) 255);
        }
        assertTrue(HyperionScreenEncoder.isFrameWhite(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameWhite_blackFrameIsNotWhite() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        assertFalse(HyperionScreenEncoder.isFrameWhite(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameWhite_normalContentIsNotWhite() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int off = y * ROW + x * 4;
                buf.put(off, (byte) ((x * 4) & 0xFF));
                buf.put(off + 1, (byte) ((y * 4) & 0xFF));
                buf.put(off + 2, (byte) 100);
            }
        }
        assertFalse(HyperionScreenEncoder.isFrameWhite(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameWhite_mostPixelsWhiteIsWhite() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        for (int i = 0; i < buf.capacity(); i += 4) {
            buf.put(i, (byte) 255);
            buf.put(i + 1, (byte) 255);
            buf.put(i + 2, (byte) 255);
            buf.put(i + 3, (byte) 255);
        }
        // a small dark block in the corner is far below the 90% fill threshold
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int off = y * ROW + x * 4;
                buf.put(off, (byte) 10);
                buf.put(off + 1, (byte) 10);
                buf.put(off + 2, (byte) 10);
            }
        }
        assertTrue(HyperionScreenEncoder.isFrameWhite(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameWhite_brightButNotWhiteIsNotWhite() {
        ByteBuffer buf = ByteBuffer.allocate(ROW * H);
        for (int i = 0; i < buf.capacity(); i++) {
            buf.put(i, (byte) 200);
        }
        assertFalse(HyperionScreenEncoder.isFrameWhite(buf, W, H, ROW, 4));
    }

    @Test
    public void isFrameWhite_invalidInputsReturnFalse() {
        assertFalse(HyperionScreenEncoder.isFrameWhite(null, W, H, ROW, 4));
        assertFalse(HyperionScreenEncoder.isFrameWhite(ByteBuffer.allocate(100), 0, H, ROW, 4));
        assertFalse(HyperionScreenEncoder.isFrameWhite(ByteBuffer.allocate(100), W, 0, ROW, 4));
    }
}
