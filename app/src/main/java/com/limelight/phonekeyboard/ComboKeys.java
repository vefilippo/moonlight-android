package com.limelight.phonekeyboard;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.Locale;

/** Wire names for keyboard shortcuts sent from the phone page (e.g. Win + Tab, Ctrl + Shift + Esc). */
public final class ComboKeys {
    // Windows virtual-key codes not defined in KeyboardTranslator.
    static final int VK_DELETE = 0x2E;
    static final int VK_OEM_PERIOD = 0xBE;

    private ComboKeys() {}

    /** KeyboardPacket modifier flag for a modifier wire name, or 0 if unknown. */
    public static byte modifierFlag(String name) {
        if (name == null) return 0;
        switch (name) {
            case "ctrl": return KeyboardPacket.MODIFIER_CTRL;
            case "shift": return KeyboardPacket.MODIFIER_SHIFT;
            case "alt": return KeyboardPacket.MODIFIER_ALT;
            case "win": return KeyboardPacket.MODIFIER_META;
            default: return 0;
        }
    }

    /**
     * Virtual-key code for a combo key wire name, or -1 if unknown. Accepts the special key names,
     * a single letter or digit, "space", ".", "del", "home", "end", "pgup", "pgdn" and "f1".."f12".
     */
    public static int vkFor(String name) {
        if (name == null || name.isEmpty()) return -1;
        SpecialKey special = SpecialKey.fromWireName(name);
        if (special != null) return special.vk();
        if (name.length() == 1) {
            char c = Character.toUpperCase(name.charAt(0));
            if (c >= 'A' && c <= 'Z') return KeyboardTranslator.VK_A + (c - 'A');
            if (c >= '0' && c <= '9') return KeyboardTranslator.VK_0 + (c - '0');
            if (c == ' ') return KeyboardTranslator.VK_SPACE;
            if (c == '.') return VK_OEM_PERIOD;
            return -1;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "space": return KeyboardTranslator.VK_SPACE;
            case "del": return VK_DELETE;
            case "home": return KeyboardTranslator.VK_HOME;
            case "end": return KeyboardTranslator.VK_END;
            case "pgup": return KeyboardTranslator.VK_PAGE_UP;
            case "pgdn": return KeyboardTranslator.VK_PAGE_DOWN;
            default: break;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.length() <= 3 && lower.charAt(0) == 'f') {
            try {
                int n = Integer.parseInt(lower.substring(1));
                if (n >= 1 && n <= 12) return KeyboardTranslator.VK_F1 + (n - 1);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }
}
