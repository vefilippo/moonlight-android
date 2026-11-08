package com.limelight.phonekeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.binding.input.KeyboardTranslator;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class TextSplitterTest {
    @Test public void split_lettersDigitsSpaceAreKeys() {
        assertEquals(Arrays.asList(
            TypingAction.key((short) KeyboardTranslator.VK_A, false),
            TypingAction.key((short) (KeyboardTranslator.VK_A + 1), true),   // 'B'
            TypingAction.key((short) (KeyboardTranslator.VK_0 + 7), false),  // '7'
            TypingAction.key((short) KeyboardTranslator.VK_SPACE, false)),
            TextSplitter.split("aB7 "));
    }
    @Test public void split_symbolsAndAccentsBecomeOneTextRunInOrder() {
        assertEquals(Arrays.asList(
            TypingAction.key((short) (KeyboardTranslator.VK_A + 15), false), // 'p'
            TypingAction.text("@è!"),
            TypingAction.key((short) (KeyboardTranslator.VK_0 + 1), false)), // '1'
            TextSplitter.split("p@è!1"));
    }
    @Test public void split_keepsSurrogatePairsInOneTextRun() {
        assertEquals(Arrays.asList(TypingAction.text("😀€")), TextSplitter.split("😀€"));
    }
    @Test public void split_newlinesBecomeSpaces() {
        assertEquals(TextSplitter.split("a b c d"), TextSplitter.split("a\r\nb\nc\rd"));
    }
    @Test public void split_emptyAndNullGiveEmptyList() {
        assertTrue(TextSplitter.split("").isEmpty());
        assertTrue(TextSplitter.split(null).isEmpty());
    }
    @Test public void utf8Chunks_neverSplitsCodePoints() {
        String s = "è".repeat(300);                  // 600 bytes
        List<String> chunks = TextSplitter.utf8Chunks(s, 512);
        assertEquals(s, String.join("", chunks));
        for (String c : chunks) assertTrue(c.getBytes(StandardCharsets.UTF_8).length <= 512);
        assertEquals(2, chunks.size());
    }
    @Test public void specialKey_wireNames() {
        assertEquals(SpecialKey.ESCAPE, SpecialKey.fromWireName("esc"));
        assertEquals((short) KeyboardTranslator.VK_RETURN, SpecialKey.ENTER.vk());
        assertNull(SpecialKey.fromWireName("f13"));
        assertNull(SpecialKey.fromWireName(null));
    }
}
