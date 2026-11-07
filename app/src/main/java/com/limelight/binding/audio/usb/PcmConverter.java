package com.limelight.binding.audio.usb;

/**
 * Allocation-free PCM helpers. Callers own all buffers.
 */
final class PcmConverter {
    static final int MAX_GAIN_LEVEL = 15;
    static final int DEFAULT_GAIN_LEVEL = 9;

    private static final float MIX = 0.707f;

    private PcmConverter() {}

    private static short clamp(float v) {
        if (v > 32767f) return 32767;
        if (v < -32768f) return -32768;
        return (short) v;
    }

    /** Converts interleaved input (1/2/6/8 ch, Moonlight order) to outChannels. Returns frames. */
    static int toDeviceChannels(short[] in, int inFrames, int inChannels, int outChannels, short[] out) {
        for (int f = 0; f < inFrames; f++) {
            int b = f * inChannels;
            short l, r;
            if (inChannels == 1) {
                l = r = in[b];
            } else if (inChannels >= 6) {
                float fl = in[b] + MIX * in[b + 2] + MIX * in[b + 4];
                float fr = in[b + 1] + MIX * in[b + 2] + MIX * in[b + 5];
                if (inChannels >= 8) {
                    fl += MIX * in[b + 6];
                    fr += MIX * in[b + 7];
                }
                l = clamp(fl);
                r = clamp(fr);
            } else {
                l = in[b];
                r = in[b + 1];
            }
            int o = f * outChannels;
            if (outChannels == 1) {
                out[o] = (short) ((l + r) / 2);
            } else {
                out[o] = l;
                out[o + 1] = r;
                for (int c = 2; c < outChannels; c++) out[o + c] = 0;
            }
        }
        return inFrames;
    }

    /** Packs 16-bit samples little-endian, left-justified in subframeBytes (2..4). Returns bytes written. */
    static int pack(short[] in, int samples, int subframeBytes, float gain, byte[] out) {
        int pad = subframeBytes - 2;
        int o = 0;
        for (int i = 0; i < samples; i++) {
            short s = clamp(in[i] * gain);
            for (int p = 0; p < pad; p++) out[o++] = 0;
            out[o++] = (byte) s;
            out[o++] = (byte) (s >> 8);
        }
        return o;
    }

    /** Takes the top 16 bits of each subframe and averages channels. Returns frames. */
    static int unpackToMono(byte[] in, int length, int channels, int subframeBytes, short[] out) {
        int frameBytes = channels * subframeBytes;
        int frames = length / frameBytes;
        for (int f = 0; f < frames; f++) {
            int base = f * frameBytes;
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int end = base + (c + 1) * subframeBytes;
                sum += (short) (((in[end - 1] & 0xFF) << 8) | (in[end - 2] & 0xFF));
            }
            out[f] = (short) (sum / channels);
        }
        return frames;
    }

    static float gainForLevel(int level) {
        if (level < 0) level = 0;
        if (level > MAX_GAIN_LEVEL) level = MAX_GAIN_LEVEL;
        float g = level / (float) MAX_GAIN_LEVEL;
        return g * g;
    }
}
