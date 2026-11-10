package com.limelight.phonekeyboard;

import java.util.Objects;

/**
 * One step of typing on the PC: a key press (optionally shifted), a shortcut (a key pressed while
 * modifiers are held) or a run of Unicode text.
 */
public final class TypingAction {
    public enum Kind { KEY, COMBO, TEXT }

    private final Kind kind;
    private final short vk;
    private final boolean shift;
    private final byte modifiers;
    private final String text;

    private TypingAction(Kind kind, short vk, boolean shift, byte modifiers, String text) {
        this.kind = kind;
        this.vk = vk;
        this.shift = shift;
        this.modifiers = modifiers;
        this.text = text;
    }

    public static TypingAction key(short vk, boolean shift) {
        return new TypingAction(Kind.KEY, vk, shift, (byte) 0, null);
    }

    /** vk pressed while the KeyboardPacket modifier flags are held; vk 0 taps only the modifiers. */
    public static TypingAction combo(byte modifiers, short vk) {
        return new TypingAction(Kind.COMBO, vk, false, modifiers, null);
    }

    public static TypingAction text(String s) {
        return new TypingAction(Kind.TEXT, (short) 0, false, (byte) 0, s);
    }

    public Kind kind() { return kind; }
    public short vk() { return vk; }
    public boolean shift() { return shift; }
    public byte modifiers() { return modifiers; }
    public String text() { return text; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TypingAction)) return false;
        TypingAction a = (TypingAction) o;
        return kind == a.kind && vk == a.vk && shift == a.shift && modifiers == a.modifiers
                && Objects.equals(text, a.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, vk, shift, modifiers, text);
    }

    @Override
    public String toString() {
        // Text content is deliberately not printed (never logged); only its length.
        if (kind == Kind.KEY) {
            return "TypingAction{KEY vk=" + vk + ", shift=" + shift + "}";
        }
        if (kind == Kind.COMBO) {
            return "TypingAction{COMBO vk=" + vk + ", modifiers=" + modifiers + "}";
        }
        return "TypingAction{TEXT length=" + (text == null ? 0 : text.length()) + "}";
    }
}
