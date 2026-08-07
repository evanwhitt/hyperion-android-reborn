package com.hyperion.grabber.common.util;

public final class HdrConverter {
    public static final int COLOR_TRANSFER_PQ = 7;
    public static final int COLOR_TRANSFER_HLG = 8;

    public static boolean isHdrTransfer(int transfer) {
        return transfer == COLOR_TRANSFER_PQ || transfer == COLOR_TRANSFER_HLG;
    }

    public static byte[] buildLut(int transfer) {
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            float out;
            if (transfer == COLOR_TRANSFER_PQ) {
                out = (float) Math.pow(v, 1f / 2.2f);
            } else {
                out = srgbEncode(Math.min(hlgEotf(v), 1f));
            }
            lut[i] = (byte) Math.round(Math.min(1f, Math.max(0f, out)) * 255f);
        }
        return lut;
    }

    public static float hlgEotf(float v) {
        float a = 0.17883277f;
        float b = 0.28466892f;
        float c = 0.55991073f;
        if (v <= 0.5f) {
            return v * v / 3f;
        }
        return ((float) Math.exp((v - b) / a) + c) / 12f;
    }

    public static float srgbEncode(float lin) {
        if (lin <= 0.0031308f) {
            return 12.92f * lin;
        }
        return 1.055f * (float) Math.pow(lin, 1f / 2.4f) - 0.055f;
    }
}
