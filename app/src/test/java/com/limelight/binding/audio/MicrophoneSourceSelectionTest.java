package com.limelight.binding.audio;

import static com.limelight.binding.audio.MicrophoneCaptureManager.shouldUseUsbHeadset;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MicrophoneSourceSelectionTest {
    @Test public void defaultUsesReadyHeadset() { assertTrue(shouldUseUsbHeadset(0, true)); }
    @Test public void explicitOtherMicWins() { assertFalse(shouldUseUsbHeadset(7, true)); }
    @Test public void explicitHeadsetPick() { assertTrue(shouldUseUsbHeadset(-2, true)); assertFalse(shouldUseUsbHeadset(-2, false)); }
    @Test public void noHeadsetNoUsb() { assertFalse(shouldUseUsbHeadset(0, false)); }
    @Test public void headsetIdIsMinusTwo() { assertEquals(-2, MicrophoneCaptureManager.USB_HEADSET_DEVICE_ID); }
}
