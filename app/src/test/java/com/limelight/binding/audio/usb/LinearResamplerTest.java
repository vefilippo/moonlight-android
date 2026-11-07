package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LinearResamplerTest {
    @Test public void identityWhenRatesEqual() {
        LinearResampler r = new LinearResampler(48000, 48000, 2);
        short[] in = {1, 2, 3, 4, 5, 6};
        short[] out = new short[6];
        assertEquals(3, r.process(in, 3, out));
        assertArrayEquals(in, out);
    }

    @Test public void upsample16kTo48kTriplesFrameCount() {
        LinearResampler r = new LinearResampler(16000, 48000, 1);
        short[] out = new short[LinearResampler.maxOutputFrames(160, 16000, 48000)];
        assertEquals(480, r.process(new short[160], 160, out), 1);

        r = new LinearResampler(16000, 48000, 1);
        int total = 0;
        for (int i = 0; i < 10; i++) total += r.process(new short[160], 160, out);
        assertEquals(4800, total, 1);
    }

    @Test public void downsample48kTo441kKeepsLongTermRatio() {
        LinearResampler r = new LinearResampler(48000, 44100, 2);
        short[] in = new short[240 * 2];
        short[] out = new short[LinearResampler.maxOutputFrames(240, 48000, 44100) * 2];
        int total = 0;
        for (int i = 0; i < 200; i++) total += r.process(in, 240, out);
        assertEquals(44100, total, 2);
    }

    @Test public void continuousAcrossCalls() {
        short[] ramp = new short[200];
        for (int i = 0; i < ramp.length; i++) ramp[i] = (short) (i * 100);
        LinearResampler whole = new LinearResampler(16000, 48000, 1);
        short[] expected = new short[LinearResampler.maxOutputFrames(200, 16000, 48000)];
        int n = whole.process(ramp, 200, expected);

        LinearResampler split = new LinearResampler(16000, 48000, 1);
        short[] got = new short[expected.length];
        short[] a = new short[80], b = new short[120];
        System.arraycopy(ramp, 0, a, 0, 80);
        System.arraycopy(ramp, 80, b, 0, 120);
        int n1 = split.process(a, 80, got);
        short[] tmp = new short[LinearResampler.maxOutputFrames(120, 16000, 48000)];
        int n2 = split.process(b, 120, tmp);
        System.arraycopy(tmp, 0, got, n1, n2);
        assertEquals(n, n1 + n2);
        for (int i = 0; i < n; i++) assertEquals("i=" + i, expected[i], got[i], 1);
    }
}
