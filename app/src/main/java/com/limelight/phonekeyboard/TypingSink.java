package com.limelight.phonekeyboard;

/**
 * Destination for phone input on the PC. Keyboard calls come from the hub's background thread;
 * mouse calls come from the HTTP thread, serialised by the hub.
 */
public interface TypingSink {
    /** A full key press: down, then up (with Shift held around it when requested). */
    void sendKey(short vk, boolean shift);

    /**
     * Holds the modifiers (KeyboardPacket MODIFIER_* flags), presses vk, then releases everything.
     * vk 0 just taps the modifiers (e.g. Win alone opens Start).
     */
    void sendCombo(byte modifiers, short vk);

    void sendText(String text);

    void sendMouseMove(short dx, short dy);

    /** button is a MouseButtonPacket BUTTON_* value. */
    void sendMouseButton(byte button, boolean down);

    /** High-resolution vertical scroll; 120 is one wheel notch, positive scrolls up. */
    void sendMouseScroll(short amount);
}
