package com.limelight.binding.audio.usb;

import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Routes the headset receiver's wheel (volume key events from its HID input device)
 * to the headset volume level (hardware feature-unit volume, else software gain) while
 * Artemis drives the headset. Volume keys from any other device (e.g. the TV remote) are
 * left alone.
 */
public final class UsbHeadsetVolumeKeys {
    /** Receives the new headset volume as a percentage, 0..100. */
    public interface VolumeSink {
        void show(int percent);
    }

    private UsbHeadsetVolumeKeys() {}

    static boolean matches(int keyCode, int eventVendorId, int eventProductId,
                           int headsetVendorId, int headsetProductId) {
        return (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && eventVendorId == headsetVendorId
                && eventProductId == headsetProductId;
    }

    static int nextLevel(int level, int keyCode) {
        return stepLevel(level, keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
    }

    /** level + delta, clamped to 0..MAX_GAIN_LEVEL. */
    static int stepLevel(int level, int delta) {
        return Math.max(0, Math.min(PcmConverter.MAX_GAIN_LEVEL, level + delta));
    }

    /** Gain level (0..MAX_GAIN_LEVEL) as the percentage shown to the user. */
    public static int percent(int level) {
        return Math.round(level * 100f / PcmConverter.MAX_GAIN_LEVEL);
    }

    /** @return true if the event was consumed. */
    public static boolean handle(KeyEvent e, UsbAudioManager m, VolumeSink sink, boolean down) {
        UsbHeadset h = m.activeHeadset();
        if (h == null) return false;
        InputDevice d = e.getDevice();
        if (d == null) return false;
        if (!matches(e.getKeyCode(), d.getVendorId(), d.getProductId(),
                h.vendorId(), h.productId())) {
            return false;
        }
        if (down) {
            int level = nextLevel(m.getGainLevel(), e.getKeyCode());
            m.setGainLevel(level);
            sink.show(percent(level));
        }
        return true;
    }
}
