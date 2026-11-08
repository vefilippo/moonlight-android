package com.limelight.phonekeyboard;

import com.limelight.binding.input.KeyboardTranslator;

/** Non-text keys the phone page can press, addressed by their wire names. */
public enum SpecialKey {
    ENTER("enter", KeyboardTranslator.VK_RETURN),
    BACKSPACE("backspace", KeyboardTranslator.VK_BACK_SPACE),
    TAB("tab", KeyboardTranslator.VK_TAB),
    ESCAPE("esc", KeyboardTranslator.VK_ESCAPE),
    UP("up", KeyboardTranslator.VK_UP),
    DOWN("down", KeyboardTranslator.VK_DOWN),
    LEFT("left", KeyboardTranslator.VK_LEFT),
    RIGHT("right", KeyboardTranslator.VK_RIGHT);

    private final String wireName;
    private final short vk;

    SpecialKey(String wireName, int vk) {
        this.wireName = wireName;
        this.vk = (short) vk;
    }

    public short vk() {
        return vk;
    }

    /** Returns the key for a wire name, or null if unknown or null. */
    public static SpecialKey fromWireName(String name) {
        if (name == null) return null;
        for (SpecialKey k : values()) {
            if (k.wireName.equals(name)) return k;
        }
        return null;
    }
}
