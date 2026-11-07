package com.limelight.binding.audio.usb;

import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;
import static com.limelight.binding.audio.usb.UsbHeadsetVolumeKeys.matches;
import static com.limelight.binding.audio.usb.UsbHeadsetVolumeKeys.nextLevel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbHeadsetVolumeKeysTest {
    @Test public void headsetWheelMatches() { assertTrue(matches(KEYCODE_VOLUME_UP, 0x046d, 0x0adf, 0x046d, 0x0adf)); }
    @Test public void tvRemoteVolumeIsNotConsumed() { assertFalse(matches(KEYCODE_VOLUME_UP, 0x0957, 0x0001, 0x046d, 0x0adf)); }
    @Test public void otherKeysFromHeadsetIgnored() { assertFalse(matches(KEYCODE_MEDIA_PLAY_PAUSE, 0x046d, 0x0adf, 0x046d, 0x0adf)); }
    @Test public void levelClamps() {
        assertEquals(15, nextLevel(15, KEYCODE_VOLUME_UP));
        assertEquals(0, nextLevel(0, KEYCODE_VOLUME_DOWN));
        assertEquals(8, nextLevel(9, KEYCODE_VOLUME_DOWN));
    }
}
