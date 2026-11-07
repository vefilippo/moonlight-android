package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbAudioPermissionPolicyTest {
    private final UsbAudioPermissionPolicy p = new UsbAudioPermissionPolicy();

    @Test
    public void unknownHeadsetIsPrompted() {
        assertTrue(p.shouldPrompt("046d:0adf"));
    }

    @Test
    public void deniedHeadsetIsNotPromptedAgainUntilReattached() {
        p.onDenied("046d:0adf");
        assertFalse(p.shouldPrompt("046d:0adf"));
        assertTrue(p.shouldPrompt("1234:5678"));
        p.onAttached("046d:0adf");
        assertTrue(p.shouldPrompt("046d:0adf"));
    }

    @Test
    public void deviceKeyIsLowercaseFourDigitHex() {
        assertTrue("046d:0adf".equals(UsbAudioPermissionPolicy.deviceKey(0x046D, 0x0ADF)));
    }
}
