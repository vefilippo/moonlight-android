package com.limelight.binding.input.driver;

import android.view.InputDevice;

/**
 * Decides whether Android already drives a USB device as a gamepad, based on the
 * input devices that share its VID/PID. Keyboard/mouse-only matches (e.g. the HID
 * interfaces of an Xbox-mode adapter on a kernel without xpad) don't count.
 */
final class UsbInputRecognition {
    private UsbInputRecognition() {}

    static boolean anyGamepad(int[] sources, boolean[] hasButtonA) {
        for (int i = 0; i < sources.length; i++) {
            if (isGamepad(sources[i], hasButtonA[i])) {
                return true;
            }
        }
        return false;
    }

    static boolean isGamepad(int sources, boolean hasButtonA) {
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            return true;
        }
        return (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && hasButtonA;
    }
}
