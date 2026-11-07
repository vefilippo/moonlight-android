package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;

public class UsbHeadsetPcmSourceTest {
    /** Returns queued chunks once each; then the configured idle result. */
    private static final class FakeInput implements UsbHeadsetPcmSource.CaptureInput {
        final UacStreamConfig cfg;
        final Deque<byte[]> chunks = new ArrayDeque<>();
        int idleResult;
        int lastTimeoutMs = -1;
        int closeCount;

        FakeInput(int channels, int subframeBytes, int rate) {
            UacDescriptors.AltSetting alt = new UacDescriptors.AltSetting();
            alt.isInput = true;
            alt.channels = channels;
            alt.subframeBytes = subframeBytes;
            alt.bitResolution = subframeBytes * 8;
            cfg = new UacStreamConfig(alt, rate);
        }

        @Override public UacStreamConfig config() { return cfg; }
        @Override public boolean start() { return true; }

        @Override
        public int read(byte[] buf, int max, int timeoutMs) {
            lastTimeoutMs = timeoutMs;
            byte[] c = chunks.poll();
            if (c == null) return idleResult;
            int n = Math.min(max, c.length);
            System.arraycopy(c, 0, buf, 0, n);
            return n;
        }

        @Override public void close() { closeCount++; }
    }

    private static short[] bigBuffer() {
        return new short[48000];
    }

    @Test
    public void converts16kMonoTo48k() {
        FakeInput in = new FakeInput(1, 2, 16000);
        byte[] chunk = new byte[256 * 2];
        for (int i = 0; i < 256; i++) {
            short s = (short) (i * 10);
            chunk[2 * i] = (byte) s;
            chunk[2 * i + 1] = (byte) (s >> 8);
        }
        in.chunks.add(chunk);
        UsbHeadsetPcmSource src = new UsbHeadsetPcmSource(in);
        int n = src.read(bigBuffer());
        assertTrue("got " + n, Math.abs(n - 768) <= 1);
        assertEquals(100, in.lastTimeoutMs);
    }

    @Test
    public void converts48kStereo24ToMono() {
        FakeInput in = new FakeInput(2, 3, 48000);
        int frames = 48;
        byte[] chunk = new byte[frames * 6];
        for (int f = 0; f < frames; f++) {
            int b = f * 6;
            // 24-bit little-endian: low byte, then the 16-bit value in the top two bytes
            chunk[b] = 0; chunk[b + 1] = 0x00; chunk[b + 2] = 0x10; // L = 0x1000
            chunk[b + 3] = 0; chunk[b + 4] = 0x00; chunk[b + 5] = 0x30; // R = 0x3000
        }
        in.chunks.add(chunk);
        UsbHeadsetPcmSource src = new UsbHeadsetPcmSource(in);
        short[] out = bigBuffer();
        int n = src.read(out);
        assertEquals(frames, n);
        for (int i = 0; i < n; i++) {
            assertEquals(0x2000, out[i]);
        }
    }

    @Test
    public void timeoutReturnsZero() {
        FakeInput in = new FakeInput(1, 2, 48000);
        in.idleResult = 0;
        assertEquals(0, new UsbHeadsetPcmSource(in).read(bigBuffer()));
    }

    @Test
    public void usbSourceReportsEndWhenDeviceGone() {
        FakeInput in = new FakeInput(1, 2, 48000);
        in.idleResult = -19;
        UsbHeadsetPcmSource src = new UsbHeadsetPcmSource(in);
        assertTrue(src.read(bigBuffer()) < 0);
    }

    @Test
    public void closeClosesInputOnceAndEndsReads() {
        FakeInput in = new FakeInput(1, 2, 48000);
        UsbHeadsetPcmSource src = new UsbHeadsetPcmSource(in);
        src.close();
        src.close();
        assertEquals(1, in.closeCount);
        assertTrue(src.read(bigBuffer()) < 0);
    }

    @Test
    public void readNeverOverflowsCallerBuffer() {
        FakeInput in = new FakeInput(1, 2, 16000);
        in.chunks.add(new byte[2000 * 2]);
        UsbHeadsetPcmSource src = new UsbHeadsetPcmSource(in);
        short[] small = new short[960];
        int n = src.read(small);
        assertTrue(n > 0 && n <= small.length);
    }
}
