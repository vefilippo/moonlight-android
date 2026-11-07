package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PcmConverterTest {
    @Test public void downmix51ToStereo() {
        short[] out = new short[2];
        assertEquals(1, PcmConverter.toDeviceChannels(new short[]{1000, 0, 1000, 30000, 0, 0}, 1, 6, 2, out));
        assertEquals(1707, out[0], 1);
        assertEquals(707, out[1], 1);
    }

    @Test public void downmix71UsesSideChannels() {
        short[] out = new short[2];
        PcmConverter.toDeviceChannels(new short[]{0, 0, 0, 0, 0, 0, 1000, 2000}, 1, 8, 2, out);
        assertEquals(707, out[0], 1);
        assertEquals(1414, out[1], 1);
    }

    @Test public void stereoToMonoAverages() {
        short[] out = new short[1];
        assertEquals(1, PcmConverter.toDeviceChannels(new short[]{1000, 3000}, 1, 2, 1, out));
        assertEquals(2000, out[0]);
    }

    @Test public void stereoToMoreChannelsZeroFills() {
        short[] out = new short[]{9, 9, 9, 9};
        PcmConverter.toDeviceChannels(new short[]{5, 6}, 1, 2, 4, out);
        assertArrayEquals(new short[]{5, 6, 0, 0}, out);
    }

    @Test public void monoInputMapsToBoth() {
        short[] out = new short[2];
        PcmConverter.toDeviceChannels(new short[]{7}, 1, 1, 2, out);
        assertArrayEquals(new short[]{7, 7}, out);
    }

    @Test public void downmixClamps() {
        short[] in = new short[6];
        java.util.Arrays.fill(in, (short) 30000);
        short[] out = new short[2];
        PcmConverter.toDeviceChannels(in, 1, 6, 2, out);
        assertEquals(32767, out[0]);
        assertEquals(32767, out[1]);
    }

    @Test public void pack16AppliesGainLittleEndian() {
        byte[] out = new byte[2];
        assertEquals(2, PcmConverter.pack(new short[]{0x1234}, 1, 2, 1f, out));
        assertArrayEquals(new byte[]{0x34, 0x12}, out);
        PcmConverter.pack(new short[]{0x1234}, 1, 2, 0.5f, out);
        assertEquals(0x091A, ((out[1] & 0xFF) << 8) | (out[0] & 0xFF));
    }

    @Test public void pack24LeftJustified() {
        byte[] out = new byte[3];
        assertEquals(3, PcmConverter.pack(new short[]{0x1234}, 1, 3, 1f, out));
        assertArrayEquals(new byte[]{0x00, 0x34, 0x12}, out);
    }

    @Test public void pack32LeftJustified() {
        byte[] out = new byte[4];
        assertEquals(4, PcmConverter.pack(new short[]{0x1234}, 1, 4, 1f, out));
        assertArrayEquals(new byte[]{0x00, 0x00, 0x34, 0x12}, out);
    }

    @Test public void packClampsAfterGain() {
        byte[] out = new byte[2];
        PcmConverter.pack(new short[]{30000}, 1, 2, 2f, out);
        assertEquals(32767, (short) (((out[1] & 0xFF) << 8) | (out[0] & 0xFF)));
        PcmConverter.pack(new short[]{-30000}, 1, 2, 2f, out);
        assertEquals(-32768, (short) (((out[1] & 0xFF) << 8) | (out[0] & 0xFF)));
    }

    @Test public void unpack24StereoToMono() {
        byte[] in = {0x00, 0x00, 0x10, 0x00, 0x00, 0x30};
        short[] out = new short[1];
        assertEquals(1, PcmConverter.unpackToMono(in, 6, 2, 3, out));
        assertEquals(0x2000, out[0]);
    }

    @Test public void unpack16Mono() {
        byte[] in = {0x34, 0x12, (byte) 0xFF, (byte) 0xFF};
        short[] out = new short[2];
        assertEquals(2, PcmConverter.unpackToMono(in, 4, 1, 2, out));
        assertEquals(0x1234, out[0]);
        assertEquals(-1, out[1]);
    }

    @Test public void gainCurve() {
        assertEquals(0f, PcmConverter.gainForLevel(0), 0);
        assertEquals(1f, PcmConverter.gainForLevel(15), 0);
        assertEquals(0.36f, PcmConverter.gainForLevel(9), 0.001);
        assertEquals(1f, PcmConverter.gainForLevel(99), 0);
        assertEquals(0f, PcmConverter.gainForLevel(-3), 0);
    }
}
