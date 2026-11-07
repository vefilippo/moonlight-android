package com.limelight.binding.audio.usb;

import static com.limelight.binding.audio.usb.UsbAudioDetector.needsArtemisDriver;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbAudioDetectorTest {
    static final int[][] G435 = {{3, 0}, {1, 1}, {1, 2}, {1, 2}};

    @Test
    public void unsupportedHeadsetNeedsDriver() {
        assertTrue(needsArtemisDriver(34, G435, new int[]{9 /*HDMI*/, 2}));
    }

    @Test
    public void androidSupportedHeadsetIsLeftAlone() {
        assertFalse(needsArtemisDriver(34, G435, new int[]{22}));
        assertFalse(needsArtemisDriver(34, G435, new int[]{11}));
        assertFalse(needsArtemisDriver(34, G435, new int[]{12}));
    }

    @Test
    public void controllerIsNotAHeadset() {
        assertFalse(needsArtemisDriver(34, new int[][]{{255, 93}}, new int[0]));
    }

    @Test
    public void audioControlOnlyIsNotAHeadset() {
        assertFalse(needsArtemisDriver(34, new int[][]{{3, 0}, {1, 1}}, new int[0]));
    }

    @Test
    public void inactiveBelowApi23() {
        assertFalse(needsArtemisDriver(22, G435, new int[0]));
    }
}
