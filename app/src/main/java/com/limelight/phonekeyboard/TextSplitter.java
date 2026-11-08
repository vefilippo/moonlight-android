package com.limelight.phonekeyboard;

import com.limelight.binding.input.KeyboardTranslator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Splits text into key presses (a-z, A-Z, 0-9, space) and Unicode runs, preserving order. */
public final class TextSplitter {
    private TextSplitter() {}

    public static List<TypingAction> split(String input) {
        List<TypingAction> out = new ArrayList<>();
        if (input == null || input.isEmpty()) return out;

        String s = input.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            TypingAction key = keyFor(cp);
            if (key != null) {
                if (run.length() > 0) {
                    out.add(TypingAction.text(run.toString()));
                    run.setLength(0);
                }
                out.add(key);
            } else {
                run.appendCodePoint(cp);
            }
        }
        if (run.length() > 0) out.add(TypingAction.text(run.toString()));
        return out;
    }

    private static TypingAction keyFor(int cp) {
        if (cp >= 'a' && cp <= 'z') {
            return TypingAction.key((short) (KeyboardTranslator.VK_A + (cp - 'a')), false);
        }
        if (cp >= 'A' && cp <= 'Z') {
            return TypingAction.key((short) (KeyboardTranslator.VK_A + (cp - 'A')), true);
        }
        if (cp >= '0' && cp <= '9') {
            return TypingAction.key((short) (KeyboardTranslator.VK_0 + (cp - '0')), false);
        }
        if (cp == ' ') {
            return TypingAction.key((short) KeyboardTranslator.VK_SPACE, false);
        }
        return null;
    }

    /** Splits text into chunks of at most maxBytes UTF-8 bytes, never cutting a code point. */
    public static List<String> utf8Chunks(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < utf8.length) {
            int end = Math.min(offset + maxBytes, utf8.length);
            // Ensure we don't cut inside a multi-byte sequence
            while (end < utf8.length && end > offset + 1 && (utf8[end] & 0xC0) == 0x80) {
                end--;
            }
            chunks.add(new String(utf8, offset, end - offset, StandardCharsets.UTF_8));
            offset = end;
        }
        return chunks;
    }
}
