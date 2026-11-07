package com.limelight.binding.input.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;

import org.junit.Test;

public class UsbInputRecognitionTest {
    private static final int KEYBOARD = InputDevice.SOURCE_KEYBOARD;
    private static final int MOUSE = InputDevice.SOURCE_MOUSE;
    private static final int DPAD = InputDevice.SOURCE_DPAD;
    private static final int JOYSTICK = InputDevice.SOURCE_JOYSTICK;
    private static final int GAMEPAD = InputDevice.SOURCE_GAMEPAD;

    @Test
    public void gamepadSourceIsRecognized() {
        assertTrue(UsbInputRecognition.anyGamepad(new int[]{GAMEPAD | JOYSTICK}, new boolean[]{true}));
        assertTrue(UsbInputRecognition.anyGamepad(new int[]{GAMEPAD}, new boolean[]{false}));
    }

    @Test
    public void joystickWithButtonAIsRecognized() {
        assertTrue(UsbInputRecognition.anyGamepad(new int[]{JOYSTICK}, new boolean[]{true}));
    }

    @Test
    public void eightBitDoKeyboardAndMouseOnlyIsNotRecognized() {
        assertFalse(UsbInputRecognition.anyGamepad(
                new int[]{KEYBOARD | DPAD | JOYSTICK, MOUSE},
                new boolean[]{false, false}));
    }

    @Test
    public void noMatchIsNotRecognized() {
        assertFalse(UsbInputRecognition.anyGamepad(new int[]{}, new boolean[]{}));
    }

    @Test
    public void gamepadAmongKeyboardMouseIsRecognized() {
        assertTrue(UsbInputRecognition.anyGamepad(
                new int[]{KEYBOARD, MOUSE, GAMEPAD | JOYSTICK},
                new boolean[]{false, false, true}));
    }
}
