package com.limelight.phonekeyboard;

import java.util.Objects;

/** One step of typing on the PC: a key press (optionally shifted) or a run of Unicode text. */
public final class TypingAction {
    public enum Kind { KEY, TEXT }

    private final Kind kind;
    private final short vk;
    private final boolean shift;
    private final String text;

    private TypingAction(Kind kind, short vk, boolean shift, String text) {
        this.kind = kind;
        this.vk = vk;
        this.shift = shift;
        this.text = text;
    }

    public static TypingAction key(short vk, boolean shift) {
        return new TypingAction(Kind.KEY, vk, shift, null);
    }

    public static TypingAction text(String s) {
        return new TypingAction(Kind.TEXT, (short) 0, false, s);
    }

    public Kind kind() { return kind; }
    public short vk() { return vk; }
    public boolean shift() { return shift; }
    public String text() { return text; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TypingAction)) return false;
        TypingAction a = (TypingAction) o;
        return kind == a.kind && vk == a.vk && shift == a.shift && Objects.equals(text, a.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, vk, shift, text);
    }

    @Override
    public String toString() {
        // Text content is deliberately not printed (never logged); only its length.
        if (kind == Kind.KEY) {
            return "TypingAction{KEY vk=" + vk + ", shift=" + shift + "}";
        }
        return "TypingAction{TEXT length=" + (text == null ? 0 : text.length()) + "}";
    }
}
