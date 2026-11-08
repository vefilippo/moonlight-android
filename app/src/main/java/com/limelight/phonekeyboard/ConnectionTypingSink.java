package com.limelight.phonekeyboard;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.List;

/** Types into a live stream. Sleeps for pacing, so it must only run on the hub's thread. */
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
