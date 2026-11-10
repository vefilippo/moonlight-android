package com.limelight.phonekeyboard;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.List;

/**
 * Types into a live stream. Keyboard methods sleep for pacing, so they must only run on the hub's
 * thread; mouse methods don't sleep.
 */
public final class ConnectionTypingSink implements TypingSink {
    static final long KEY_PACING_MS = 8;
    static final long TEXT_CHUNK_PACING_MS = 15;
    private static final int TEXT_CHUNK_BYTES = 512;

    private final NvConnection conn;

    public ConnectionTypingSink(NvConnection conn) {
        this.conn = conn;
    }

    @Override
    public void sendKey(short vk, boolean shift) {
        if (shift) {
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LSHIFT, KeyboardPacket.KEY_DOWN,
                    (byte) 0, (byte) 0);
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, KeyboardPacket.MODIFIER_SHIFT, (byte) 0);
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, KeyboardPacket.MODIFIER_SHIFT, (byte) 0);
            conn.sendKeyboardInput((short) KeyboardTranslator.VK_LSHIFT, KeyboardPacket.KEY_UP,
                    (byte) 0, (byte) 0);
        } else {
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        }
        pause(KEY_PACING_MS);
    }

    /** Press order for held modifiers; released in reverse. */
    private static final byte[] MODIFIER_FLAGS = {
            KeyboardPacket.MODIFIER_CTRL, KeyboardPacket.MODIFIER_SHIFT,
            KeyboardPacket.MODIFIER_ALT, KeyboardPacket.MODIFIER_META };
    private static final short[] MODIFIER_VKS = {
            (short) KeyboardTranslator.VK_LCONTROL, (short) KeyboardTranslator.VK_LSHIFT,
            (short) KeyboardTranslator.VK_LMENU, (short) KeyboardTranslator.VK_LWIN };

    @Override
    public void sendCombo(byte modifiers, short vk) {
        byte held = 0;
        for (int i = 0; i < MODIFIER_FLAGS.length; i++) {
            if ((modifiers & MODIFIER_FLAGS[i]) == 0) continue;
            conn.sendKeyboardInput(MODIFIER_VKS[i], KeyboardPacket.KEY_DOWN, held, (byte) 0);
            held |= MODIFIER_FLAGS[i];
        }
        if (vk != 0) {
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, held, (byte) 0);
            pause(KEY_PACING_MS);
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, held, (byte) 0);
        }
        for (int i = MODIFIER_FLAGS.length - 1; i >= 0; i--) {
            if ((modifiers & MODIFIER_FLAGS[i]) == 0) continue;
            held &= (byte) ~MODIFIER_FLAGS[i];
            conn.sendKeyboardInput(MODIFIER_VKS[i], KeyboardPacket.KEY_UP, held, (byte) 0);
        }
        pause(KEY_PACING_MS);
    }

    @Override
    public void sendMouseMove(short dx, short dy) {
        conn.sendMouseMove(dx, dy);
    }

    @Override
    public void sendMouseButton(byte button, boolean down) {
        if (down) conn.sendMouseButtonDown(button);
        else conn.sendMouseButtonUp(button);
    }

    @Override
    public void sendMouseScroll(short amount) {
        conn.sendMouseHighResScroll(amount);
    }

    @Override
    public void sendText(String text) {
        List<String> chunks = TextSplitter.utf8Chunks(text, TEXT_CHUNK_BYTES);
        for (int i = 0; i < chunks.size(); i++) {
            conn.sendUtf8Text(chunks.get(i));
            if (i < chunks.size() - 1) pause(TEXT_CHUNK_PACING_MS);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
