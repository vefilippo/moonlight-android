package com.limelight.binding.audio.usb;

/**
 * Streaming linear-interpolation resampler for interleaved 16-bit PCM.
 * Keeps phase and the last input frame across calls. Allocates only in the constructor.
 * Phase is exact: positions are integers in units of 1/outRate input frames.
 */
final class LinearResampler {
    private final int channels;
    private final boolean passThrough;
    private final long inRate;
    private final long outRate;
    private final short[] prev;
    private long pos; // relative to the first frame of the next chunk; -outRate = prev frame
    private boolean primed;

    LinearResampler(int inRate, int outRate, int channels) {
        this.channels = channels;
        this.passThrough = inRate == outRate;
        this.inRate = inRate;
        this.outRate = outRate;
        this.pos = -(long) outRate;
        this.prev = new short[channels];
    }

    static int maxOutputFrames(int inFrames, int inRate, int outRate) {
        long num = (long) inFrames * outRate;
        return (int) ((num + inRate - 1) / inRate) + 1;
    }

    int process(short[] in, int inFrames, short[] out) {
        if (passThrough) {
            System.arraycopy(in, 0, out, 0, inFrames * channels);
            return inFrames;
        }
        if (inFrames <= 0) return 0;
        if (!primed) {
            for (int c = 0; c < channels; c++) prev[c] = in[c];
            primed = true;
        }
        long limit = (long) (inFrames - 1) * outRate;
        int n = 0;
        while (pos < limit) {
            int i0 = pos < 0 ? -1 : (int) (pos / outRate); // pos >= -outRate always
            float frac = (float) (pos - (long) i0 * outRate) / outRate;
            for (int c = 0; c < channels; c++) {
                int a = i0 < 0 ? prev[c] : in[i0 * channels + c];
                int b = in[(i0 + 1) * channels + c];
                out[n * channels + c] = (short) Math.round(a + (b - a) * frac);
            }
            n++;
            pos += inRate;
        }
        pos -= (long) inFrames * outRate;
        int last = (inFrames - 1) * channels;
        for (int c = 0; c < channels; c++) prev[c] = in[last + c];
        return n;
    }
}
