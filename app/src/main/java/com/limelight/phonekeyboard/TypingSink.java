package com.limelight.phonekeyboard;

/** Destination for typing on the PC. Called from the hub's background thread. */
public interface TypingSink {
    /** A full key press: down, then up (with Shift held around it when requested). */
    void sendKey(short vk, boolean shift);

    void sendText(String text);
}
